package zombie.savefile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Per-engine-call pooled connections for the vanilla synchronous account API. */
public final class ApocBRServerDatabase {
    private static final ThreadLocal<Lease> active = new ThreadLocal<>();
    private ApocBRServerDatabase() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("apocbr.postgres.serverDatabase.enabled",
                System.getProperty("apocbr.postgres.enabled", "false")));
    }

    public static final String PENDING="Database update queued; pending persistence";
    public static boolean queue(String sql,Object... values) {
        return queueBatch(new String[]{sql},new Object[][]{values});
    }
    public static boolean queueBatch(String[] sql,Object[][] values) {
        if(!enabled()||!ApocBRPersistence.journaling()||ApocBRPersistence.background())return false;
        java.util.List<ApocBRPersistenceOperations.Sql> statements=new java.util.ArrayList<>();
        for(int i=0;i<sql.length;i++)statements.add(new ApocBRPersistenceOperations.Sql(sql[i],java.util.Arrays.asList(values[i])));
        ApocBRPersistence.submit(new ApocBRPersistenceOperations.Account(statements));return true;
    }
    public static boolean queueUserlog(String username,String type,String text,String issuer,int amount,String capturedAt) {
        if(!enabled()||!ApocBRPersistence.journaling()||ApocBRPersistence.background())return false;
        boolean byType=type.equals("LuaChecksum")||type.equals("DupeItem");
        boolean aggregate=byType||java.util.Set.of("Kicked","Banned","SuspiciousActivity","UnauthorizedPacket").contains(type);
        if(!aggregate)return queue("INSERT INTO userlog(username,type,text,issuedby,amount,lastupdate) VALUES(?,?,?,?,?,?)",username,type,text,issuer,amount,capturedAt);
        String where=byType?"username=? AND type=?":"username=? AND type=? AND text=? AND issuedby=?";
        Object[] predicate=byType?new Object[]{username,type}:new Object[]{username,type,text,issuer};
        java.util.List<Object> update=new java.util.ArrayList<>(java.util.Arrays.asList(capturedAt,text));update.addAll(java.util.Arrays.asList(predicate));
        java.util.List<Object> insert=new java.util.ArrayList<>(java.util.Arrays.asList(username,type,text,issuer,amount,capturedAt));insert.addAll(java.util.Arrays.asList(predicate));
        return queueBatch(new String[]{"UPDATE userlog SET amount=CAST(amount AS integer)+1,lastupdate=?,text=? WHERE "+where,
            "INSERT INTO userlog(username,type,text,issuedby,amount,lastupdate) SELECT ?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM userlog WHERE "+where+")"},
            new Object[][]{update.toArray(),insert.toArray()});
    }

    private static final class Lease {
        final Connection physical;
        final Connection facade;
        int depth;
        Lease(Connection physical) {
            this.physical = physical;
            this.facade = proxy(Connection.class, physical);
        }
    }

    public static final class Call implements AutoCloseable {
        private final Connection connection;
        private final Lease lease;
        private final ApocBRForegroundDatabase budget;
        private boolean closed;
        private Call(Connection connection, Lease lease,ApocBRForegroundDatabase budget) { this.connection = connection; this.lease = lease;this.budget=budget; }
        public Connection connection() { return connection; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try {if (lease != null && --lease.depth == 0) {
                active.remove();
                try { lease.physical.close(); }
                catch (SQLException failure) { throw new IllegalStateException("PostgreSQL connection release failed"); }
            }}finally{if(budget!=null)budget.close();}
        }
    }

    public static Call open(Connection sqlite) {
        if (!enabled()) return new Call(sqlite, null,null);
        ApocBRForegroundDatabase budget=ApocBRForegroundDatabase.begin();
        try {
        Lease lease = active.get();
        if (lease == null) {
            try { lease = new Lease(ApocBRPostgresConnector.borrowServerConnection()); }
            catch (SQLException | LinkageError failure) {
                // No SQLite fallback and no credential-bearing driver exception chain.
                throw new IllegalStateException("PostgreSQL server database unavailable; login/operation aborted");
            }
            active.set(lease);
        }
        lease.depth++;
        return new Call(lease.facade, lease,budget);
        }catch(RuntimeException|Error failure){budget.close();throw failure;}
    }

    public static void validate(Connection connection) throws SQLException {
        String[] queries = {
            "SELECT id,world,username,password,lastconnection,role,authtype,googlekey,steamid,ownerid,displayname FROM public.whitelist WHERE false",
            "SELECT steamid FROM public.allowedsteamid WHERE false",
            "SELECT steamid,reason FROM public.bannedid WHERE false",
            "SELECT ip,username,reason FROM public.bannedip WHERE false",
            "SELECT id,name,description,colorr,colorg,colorb,readonly,position FROM public.role WHERE false",
            "SELECT name,role FROM public.capabilities WHERE false",
            "SELECT name,role FROM public.defaultroles WHERE false",
            "SELECT id,username,type,text,issuedby,amount,lastupdate FROM public.userlog WHERE false",
            "SELECT id,message,author,answeredid,viewed FROM public.tickets WHERE false"
        };
        for (String query : queries) {
            try (PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet ignored = statement.executeQuery()) { }
            catch (SQLException failure) {
                String table = query.substring(query.indexOf(" FROM ") + 6, query.indexOf(" WHERE "));
                throw new SQLException("PostgreSQL migration contract missing or incompatible: " + table,
                        failure.getSQLState());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object target) {
        return (T) Proxy.newProxyInstance(ApocBRServerDatabase.class.getClassLoader(), new Class<?>[]{type},
                (object, method, arguments) -> invoke(target, method, arguments));
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            Object result = method.invoke(target, arguments);
            if (result instanceof PreparedStatement statement) {
                statement.setQueryTimeout(2);
                return proxy(PreparedStatement.class, statement);
            }
            if (result instanceof Statement statement) {
                statement.setQueryTimeout(2);
                return proxy(Statement.class, statement);
            }
            if (result instanceof ResultSet rows) return proxy(ResultSet.class, rows);
            return result;
        } catch (InvocationTargetException wrapper) {
            Throwable failure = wrapper.getCause();
            if (failure instanceof SQLException sql) {
                // Native exception loggers must not print PG row DETAIL/passwords/2FA secrets.
                throw new SQLException("PostgreSQL server database operation failed; SQLState="
                        + sql.getSQLState(), sql.getSQLState(), sql.getErrorCode());
            }
            throw failure;
        }
    }
}
