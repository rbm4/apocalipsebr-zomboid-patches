package zombie.savefile;

import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** One deadline shared by nested calls in a packet/API workflow. */
public final class ApocBRForegroundDatabase implements AutoCloseable {
    private static final ThreadLocal<Long> deadline=new ThreadLocal<>();
    private static final ThreadLocal<Integer> networkLimit=new ThreadLocal<>();
    private final Long previous;
    private ApocBRForegroundDatabase(int milliseconds,int networkMillis){
        previous=deadline.get();
        if(previous==null){deadline.set(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds));networkLimit.set(networkMillis);}
    }
    public static ApocBRForegroundDatabase begin(){return new ApocBRForegroundDatabase(500,250);}
    /** Connection phases perform multiple synchronous account/player reads, not one query. */
    public static ApocBRForegroundDatabase beginLogin(){return new ApocBRForegroundDatabase(
        Math.max(500,Math.min(30_000,Integer.getInteger("apocbr.postgres.loginWorkflowTimeoutMillis",5_000))),
        Math.max(250,Math.min(30_000,Integer.getInteger("apocbr.postgres.loginNetworkTimeoutMillis",3_000))));}
    static boolean withinDeadline(){Long expires=deadline.get();return expires==null||expires-System.nanoTime()>0;}
    static final class DeadlineExceededException extends SQLTimeoutException {
        DeadlineExceededException(){super("Foreground database workflow deadline exceeded","HYT00");}
    }
    // Pool contention must still fail quickly; only login socket/query waits grow.
    static int acquisitionMillis()throws SQLException {return deadline.get()==null?250:remaining(deadline.get(),250);}
    @Override public void close(){if(previous==null){deadline.remove();networkLimit.remove();}else deadline.set(previous);}
    static Connection wrap(Connection connection)throws SQLException {
        long expires=deadline.get()==null?System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250):deadline.get();
        Integer limit=networkLimit.get();
        return proxy(Connection.class,connection,connection,expires,limit==null?250:limit);
    }
    private static int remaining(long expires,int limit)throws SQLException {
        // A local workflow budget says nothing about database connectivity. In particular it
        // must not close admission globally through Persistence.failed's SQLState 08 handling.
        long left=expires-System.nanoTime();if(left<=0)throw new DeadlineExceededException();
        return (int)Math.max(1,Math.min(limit,TimeUnit.NANOSECONDS.toMillis(left)));
    }
    @SuppressWarnings("unchecked")
    private static <T>T proxy(Class<T> type,Object target,Connection connection,long expires,int limit) {
        return (T)Proxy.newProxyInstance(ApocBRForegroundDatabase.class.getClassLoader(),new Class<?>[]{type},(p,m,args)->{
            try {
                // Cleanup must work even after the deadline. Object methods must not make network calls.
                if(!m.getName().equals("close")&&!m.getName().equals("isClosed")&&m.getDeclaringClass()!=Object.class)
                    connection.setNetworkTimeout(Runnable::run,remaining(expires,limit));
                Object result=m.invoke(target,args);
                if(result instanceof PreparedStatement s)return proxy(PreparedStatement.class,s,connection,expires,limit);
                if(result instanceof Statement s)return proxy(Statement.class,s,connection,expires,limit);
                if(result instanceof ResultSet s)return proxy(ResultSet.class,s,connection,expires,limit);
                return result;
            }catch(InvocationTargetException wrapper){Throwable cause=wrapper.getCause();if(cause instanceof SQLException sql){
                ApocBRPersistence.failed(sql);
                Throwable root=sql;for(int depth=0;depth<8&&root.getCause()!=null;depth++)root=root.getCause();
                String state=sql.getSQLState();
                // Driver messages can contain connection details or SQL/player data. Log only types and budgets.
                zombie.debug.DebugLog.log("[ApocBR][Postgres] FOREGROUND_JDBC_FAILURE; SQLState="
                    +(state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN")+"; networkLimitMs="+limit+"; cause="+root.getClass().getName());
                throw new SQLException("PostgreSQL foreground operation unavailable",sql.getSQLState());}throw cause;}
            catch(SQLException error){ApocBRPersistence.failed(error);throw error;}
        });
    }
}
