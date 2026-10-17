package zombie.savefile;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import zombie.debug.DebugLog;

/** Shared game datasource; native APIs own scheduling, with legacy mirror helpers retained. */
public final class ApocBRPostgresConnector {
    private static volatile ApocBRPostgresConnector instance;
    private static boolean configured;
    private static volatile Runnable dictionaryShutdown=()->{};
    private static final String INSERT = """
        INSERT INTO public.networkplayers
        (world, username, steamid, playerindex, name, x, y, z, worldversion, isdead, splitusername, data, source_server_id, source_dictionary_namespace_id, source_dictionary_view_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String UPDATE = """
        UPDATE public.networkplayers SET world=?, username=?, steamid=?, playerindex=?,
        name=?, x=?, y=?, z=?, worldversion=?, isdead=?, splitusername=?, data=?, source_server_id=?, source_dictionary_namespace_id=?, source_dictionary_view_id=? WHERE id=?
        """;
    private static final String SELECT_USERNAME = "SELECT id FROM public.networkplayers WHERE username=? AND world=? AND playerindex=?";
    private static final String SELECT_STEAM = "SELECT id FROM public.networkplayers WHERE steamid=? AND world=? AND playerindex=?";
    private static final String WRITE_LOCK = "LOCK TABLE public.networkplayers IN SHARE ROW EXCLUSIVE MODE";

    public record Snapshot(String world, String username, String steamid, int playerIndex,
                           String name, float x, float y, float z, int worldVersion,
                           boolean dead, String splitUsername, boolean steamCoop,
                           long capturedAtMillis, byte[] data, ApocBRPayloadProvenance origin) implements java.io.Serializable {
        public Snapshot(String world,String username,String steamid,int playerIndex,String name,float x,float y,float z,int worldVersion,
                        boolean dead,String splitUsername,boolean steamCoop,long capturedAtMillis,byte[] data) {
            this(world,username,steamid,playerIndex,name,x,y,z,worldVersion,dead,splitUsername,steamCoop,capturedAtMillis,data,null);
        }
        public Snapshot {
            if (world == null || world.isBlank() || username == null || username.isBlank()
                    || playerIndex < 0 || playerIndex > 3 || data == null || data.length == 0
                    || data.length > 1_048_576 || (steamCoop && (steamid == null || steamid.isBlank()))) {
                throw new IllegalArgumentException("Invalid character snapshot");
            }
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
        int payloadBytes(){return data.length;}
    }

    public record Status(int pending, long pendingBytes, long committed, long failures,
                         long rejected, String lastError, boolean closed) {}
    public record PersistenceStatus(String state,int staged,long stagedBytes,long durable,long durableBytes,long oldestPendingMs,boolean inFlight) {}
    public static PersistenceStatus persistenceStatus(){return ApocBRPersistence.status();}

    private final DataSource datasource;
    private final Consumer<String> log;
    private final ArrayDeque<Snapshot> queue = new ArrayDeque<>();
    private long pendingBytes, committed, failures, rejected, nextAttemptNanos;
    private String lastError = "NONE";
    private boolean closed;
    private boolean connectionObserved;
    private static final int QUEUE_LIMIT = 64;
    private static final long BYTE_LIMIT = 16L * 1024 * 1024;

    // Also used by isolated tests with a fake datasource, without a running game/DB.
    ApocBRPostgresConnector(DataSource datasource, Consumer<String> log) {
        this.datasource = datasource;
        this.log = log;
    }

    private static synchronized ApocBRPostgresConnector current() {
        if (configured) return instance;
        configured = true;
        if (!Boolean.getBoolean("apocbr.postgres.enabled")) {
            DebugLog.log("[ApocBR][Postgres] disabled; enable with -Dapocbr.postgres.enabled=true before the Java main class");
            return null;
        }
        try {
            String url = System.getenv("APOCBR_POSTGRES_URL");
            if (url == null || url.isBlank()) url = System.getProperty("apocbr.postgres.url");
            if (url == null || !url.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("Invalid connector configuration");
            }
            int size = Integer.getInteger("apocbr.postgres.poolSize", 8);
            if (size < 1 || size > 32) throw new IllegalArgumentException("Invalid pool size");
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(url); // Full JDBC URL including DB, credentials and optional SSL parameters.
            pg.setConnectTimeout(3);
            pg.setSocketTimeout(3);
            pg.setTcpKeepAlive(true);
            pg.setApplicationName("ApocBR-game-persistence");
            HikariConfig config = new HikariConfig();
            config.setDataSource(pg);
            config.setPoolName("ApocBR-game-persistence");
            config.setMaximumPoolSize(size);
            config.setMinimumIdle(Math.min(2, size));
            config.setConnectionTimeout(2_000);
            config.setValidationTimeout(250);
            config.setInitializationFailTimeout(-1);
            config.setAutoCommit(false);
            config.setMaxLifetime(1_800_000);
            instance = new ApocBRPostgresConnector(new HikariDataSource(config),
                    code -> DebugLog.log("[ApocBR][Postgres] " + code));
            instance.log.accept("connector enabled; pool maximum=" + size);
        } catch (RuntimeException | LinkageError failure) {
            DebugLog.log("[ApocBR][Postgres] CONFIG_OR_DEPENDENCY_FAILURE; mirror disabled");
        }
        return instance;
    }

    /** Worker-only hook. Invalid native payloads never overwrite the PostgreSQL mirror. */
    public static void enqueue(String world, String username, String steamid, int index,
                               String name, float x, float y, float z, int version,
                               boolean dead, String splitUsername, byte[] data,
                               boolean steamCoop, long capturedAtMillis) {
        ApocBRPostgresConnector target = current();
        if (target == null) return;
        try {
            Snapshot snapshot=new Snapshot(world, username, steamid, index, name, x, y, z,
                    version, dead, splitUsername, steamCoop, capturedAtMillis, data);
            if(ApocBRPersistence.journaling())ApocBRPersistence.submit(new ApocBRPersistenceOperations.Native(snapshot));
            else target.offer(snapshot);
        } catch (IllegalArgumentException invalid) {
            target.reject("INVALID_SNAPSHOT");
        }
    }

    public static void process() {
        ApocBRPostgresConnector target = current();
        if (target != null) target.drain();
    }

    /** Preserve synchronous ServerWorldDatabase return values using a caller-owned pool lease. */
    public static Connection borrowServerConnection() throws SQLException {
        return borrowConnection(true);
    }

    public static Connection borrowVehicleConnection() throws SQLException {
        return borrowConnection(false,false);
    }

    public static Connection borrowPlayerConnection() throws SQLException { return borrowConnection(false); }

    static int dictionaryPublicationTimeoutSeconds() {
        int seconds=Integer.parseInt(System.getProperty("apocbr.postgres.dictionary.publicationTimeoutSeconds","30"));
        if(seconds<5||seconds>300)throw new IllegalArgumentException("Dictionary publication timeout must be 5..300 seconds");
        return seconds;
    }

    public static Connection borrowDictionaryConnection() throws SQLException {
        Connection connection=borrowConnection(false,false);
        try {
            // pgJDBC implements this using the socket read timeout. Hikari tracks this change
            // and restores the pool's original timeout when this lease is returned.
            connection.setNetworkTimeout(Runnable::run,(dictionaryPublicationTimeoutSeconds()+15)*1000);
            return connection;
        }catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException ignored){}throw failure;}
    }

    /** LISTEN owns an extra physical session, outside the query pool (also safe with poolSize=1). */
    public static Connection openDictionaryListenerConnection() throws SQLException {
        ApocBRPostgresConnector target=current();
        if(target==null||target.closed)throw new SQLException("PostgreSQL dictionary listener unavailable","08001");
        DataSource source=target.datasource instanceof HikariDataSource pool?pool.getDataSource():target.datasource;
        Connection connection=source.getConnection();
        try{connection.setAutoCommit(true);return connection;}catch(SQLException error){connection.close();throw error;}
    }

    public static void setDictionaryShutdown(Runnable shutdown) { dictionaryShutdown=java.util.Objects.requireNonNull(shutdown); }

    /** Native ServerPlayerDB queue owns scheduling in authoritative player mode. */
    public static void savePlayerSnapshot(Snapshot snapshot) throws SQLException {
        ApocBRPostgresConnector target = current();
        if (target == null || target.closed) throw new SQLException("PostgreSQL players unavailable", "08001");
        try {
            target.write(snapshot);
            synchronized (target) { target.committed++; target.lastError="NONE"; }
            target.log.accept("player snapshot committed; bytes=" + snapshot.data.length);
        } catch (SQLException failure) {
            synchronized (target) { target.failures++; target.lastError="SQL_FAILURE"; }
            target.log.accept("PLAYER_SQL_FAILURE; " + sqlDiagnostic(failure));
            throw new SQLException("PostgreSQL player save failed; SQLState=" + failure.getSQLState(), failure.getSQLState());
        }
    }

    public static void prepareStartup(){ApocBRPersistence.startup();}
    public static void finishStartup(){ApocBRPersistence.finishStartup();}
    public static boolean acceptingLogins(){return !ApocBRPersistence.enabled()||ApocBRPersistence.admitting();}
    static boolean playerReadable(String world,String identity,int slot,boolean coop) {
        return acceptingLogins()&&(!ApocBRPersistence.journaling()||!ApocBRPersistence.pendingPlayer(ApocBRPersistenceOperations.key(world,identity,slot,coop))
            &&!ApocBRPlayerRelationalMutations.hasPending(world,identity,slot,coop));
    }
    public static void awaitPersistence(){ApocBRPersistence.drain();}
    public static void runShutdownDatabaseWork(Runnable work){ApocBRPersistence.shutdownWork(work);}
    public static String availability(){return ApocBRPersistence.state();}
    static void writeJournalSnapshot(Snapshot snapshot)throws SQLException {
        ApocBRPostgresConnector target=current();
        if(target==null||target.closed)throw new SQLException("PostgreSQL unavailable","08001");
        target.write(snapshot);
    }
    public static void enqueuePlayerSnapshot(Snapshot snapshot){
        if(ApocBRPersistence.journaling())ApocBRPersistence.submit(new ApocBRPersistenceOperations.Native(snapshot));
        else try{savePlayerSnapshot(snapshot);}catch(SQLException error){throw new IllegalStateException("Player save failed",error);}
    }

    private static Connection borrowConnection(boolean autoCommit) throws SQLException {return borrowConnection(autoCommit,!ApocBRPersistence.background());}
    private static Connection borrowConnection(boolean autoCommit,boolean foreground) throws SQLException {
        if(ApocBRPersistence.journaling()&&foreground&&!ApocBRPersistence.reachable())
            throw new SQLException("PostgreSQL temporarily unavailable","08001");
        ApocBRPostgresConnector target = current();
        if (target == null || target.closed) throw new SQLException("PostgreSQL connector unavailable", "08001");
        try {
            Connection connection = ApocBRPersistence.journaling()&&foreground&&target.datasource instanceof HikariDataSource pool
                    ? ((com.zaxxer.hikari.pool.HikariPool)pool.getHikariPoolMXBean()).getConnection(ApocBRForegroundDatabase.acquisitionMillis())
                    : target.datasource.getConnection();
            try {
                if(ApocBRPersistence.journaling()&&foreground)connection=ApocBRForegroundDatabase.wrap(connection);
                connection.setAutoCommit(autoCommit);
                connection.setSchema("public");
                return connection;
            } catch (SQLException failure) {
                connection.close();
                throw failure;
            }
        } catch (SQLException failure) {
            ApocBRPersistence.failed(failure);
            throw new SQLException("PostgreSQL connection acquisition failed", failure.getSQLState());
        }
    }

    /** Safe observation only; does not initialize the pool or make SQL calls. */
    public static Status mirrorStatus() {
        return instance == null ? new Status(0, 0, 0, 0, 0, "DISABLED_OR_UNCONFIGURED", false) : instance.status();
    }

    /** Active shutdown replay discards after five failed attempts per operation.
     * The inactive legacy mirror retains its compatibility flush. */
    public static void shutdown() {
        if (ApocBRPersistence.enabled()) ApocBRPersistence.drain();
        if (instance == null) return;
        instance.nextAttemptNanos = 0; // One final attempt even if the normal retry delay has not expired.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (instance.status().pending() > 0 && System.nanoTime() < deadline) {
            instance.drain(1);
            if (instance.status().lastError().equals("SQL_FAILURE")) break;
        }
    }

    /** After native vehicle Reset has flushed its remaining work. */
    public static void closePool() {
        ApocBRPersistence.close();
        dictionaryShutdown.run();
        if (instance != null) instance.close();
    }

    public synchronized Status status() {
        return new Status(queue.size(), pendingBytes, committed, failures, rejected, lastError, closed);
    }

    synchronized boolean offer(Snapshot snapshot) {
        if (closed || queue.size() >= QUEUE_LIMIT || pendingBytes + snapshot.data.length > BYTE_LIMIT) {
            reject("QUEUE_FULL_OR_CLOSED");
            return false;
        }
        queue.addLast(snapshot);
        pendingBytes += snapshot.data.length;
        return true;
    }

    private synchronized void reject(String code) {
        rejected++;
        lastError = code;
        if (rejected == 1 || rejected % 100 == 0) log.accept(code + "; rejected=" + rejected);
    }

    /** Serial FIFO writes: a failed head remains ahead of newer snapshots. */
    void drain() {
        drain(4);
    }

    private void drain(int limit) {
        for (int count = 0; count < limit; count++) {
            Snapshot snapshot;
            synchronized (this) {
                if (closed || System.nanoTime() < nextAttemptNanos || queue.isEmpty()) return;
                snapshot = queue.peekFirst();
            }
            try {
                write(snapshot);
                synchronized (this) {
                    queue.removeFirst();
                    pendingBytes -= snapshot.data.length;
                    committed++;
                    lastError = "NONE";
                }
                log.accept("snapshot committed; total=" + committed + "; bytes=" + snapshot.data.length);
            } catch (SQLException | RuntimeException failure) {
                synchronized (this) {
                    failures++;
                    lastError = "SQL_FAILURE";
                    nextAttemptNanos = System.nanoTime() + 5_000_000_000L;
                    if (failures == 1 || failures % 12 == 0) {
                        String state = failure instanceof SQLException sql ? sql.getSQLState() : null;
                        log.accept("SQL_FAILURE; SQLState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "UNKNOWN")
                                + "; failures=" + failures + "; pending=" + queue.size()
                                + "; " + sqlDiagnostic(failure));
                        // Log SQL structure only: never PreparedStatement.toString() or bound values.
                        log.accept("SQL_QUERY; operation=MIRROR_CHARACTER; templates="
                                + String.join(" | ", WRITE_LOCK, SELECT_USERNAME, SELECT_STEAM, INSERT, UPDATE).replaceAll("\\s+", " ").trim());
                    }
                }
                return;
            }
        }
    }

    static String sqlDiagnostic(Throwable failure) { return sqlDiagnostic(failure,true); }

    static String sqlDiagnostic(Throwable failure,boolean playerHints) {
        StringBuilder result = new StringBuilder("exception=" + failure.getClass().getSimpleName());
        if(failure instanceof SQLException sql) {
            String state=sql.getSQLState();
            result.append("; SQLState=").append(state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN").append("; errorCode=").append(sql.getErrorCode());
        }
        if (failure instanceof PSQLException pg && pg.getServerErrorMessage() != null) {
            ServerErrorMessage error = pg.getServerErrorMessage();
            result.append("; message=").append(safeServerText(error.getMessage()));
            result.append("; schema=").append(safeServerText(error.getSchema()));
            result.append("; table=").append(safeServerText(error.getTable()));
            result.append("; column=").append(safeServerText(error.getColumn()));
            result.append("; constraint=").append(safeServerText(error.getConstraint()));
            result.append("; position=").append(error.getPosition());
            // PostgreSQL DETAIL / WHERE may contain full row values; deliberately exclude them.
        }
        // Transport errors have no PostgreSQL ServerErrorMessage. Report cause TYPES and
        // controlled classifications, never raw IOException/driver messages or SQL values.
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable cause=failure.getCause();int depth=0;
        while(cause!=null&&seen.add(cause)&&depth++<8) {
            result.append("; cause=").append(cause.getClass().getSimpleName());
            if(cause instanceof java.net.SocketTimeoutException)result.append("; transport=SOCKET_READ_TIMEOUT");
            else if(cause instanceof java.net.ConnectException)result.append("; transport=CONNECT_FAILURE");
            else if(cause instanceof java.net.SocketException)result.append("; transport=SOCKET_FAILURE");
            else if(cause instanceof java.io.EOFException)result.append("; transport=UNEXPECTED_EOF");
            else if(cause instanceof javax.net.ssl.SSLException)result.append("; transport=TLS_FAILURE");
            cause=cause.getCause();
        }
        if (playerHints && failure instanceof SQLException sql) {
            if ("42P01".equals(sql.getSQLState())) {
                result.append("; hint=undefined relation: verify backend migration and target database for public.networkplayers");
            } else if ("42P10".equals(sql.getSQLState())) {
                result.append("; hint=invalid column reference; mirror does not require an ON CONFLICT key");
            } else if ("42703".equals(sql.getSQLState())) {
                result.append("; hint=verify vanilla columns: world,username,steamid,playerindex,name,x,y,z,worldversion,isdead,splitusername,data,id");
            } else if ("21000".equals(sql.getSQLState())) {
                result.append("; hint=multiple rows match the vanilla player identity; reconcile duplicate records");
            }
        }
        return result.toString();
    }

    private static String safeServerText(String text) {
        if (text == null) return "UNKNOWN";
        String cleaned = text.replaceAll("(?i)(?:jdbc:)?postgres(?:ql)?://\\S+", "[REDACTED_URL]")
                .replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*[=:]\\s*[^\\s;,]+", "$1=[REDACTED]")
                .replaceAll("[\\r\\n\\t]", " ");
        return cleaned.substring(0, Math.min(512, cleaned.length()));
    }

    void write(Snapshot s) throws SQLException {
        try (Connection connection = datasource.getConnection()) {
            if (!connectionObserved) {
                connectionObserved = true;
                log.accept("connection acquired; PostgreSQL reachable");
            }
            connection.setAutoCommit(false);
            try {
                if(!ApocBRPersistence.claim(connection)){connection.rollback();return;}
                // The existing migration has only an id PK. Serialize lookup+insert without DDL.
                try (PreparedStatement lock = connection.prepareStatement(WRITE_LOCK)) {
                    lock.setQueryTimeout(2);
                    lock.execute();
                }
                Integer id = null;
                try (PreparedStatement lookup = connection.prepareStatement(s.steamCoop ? SELECT_STEAM : SELECT_USERNAME)) {
                    lookup.setQueryTimeout(2);
                    lookup.setString(1, s.steamCoop ? s.steamid : s.username);
                    lookup.setString(2, s.world);
                    lookup.setInt(3, s.playerIndex);
                    try (ResultSet rows = lookup.executeQuery()) {
                        if (rows.next()) {
                            id = rows.getInt(1);
                            if (rows.next()) throw new SQLException("Ambiguous character identity", "21000");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(id == null ? INSERT : UPDATE)) {
                    statement.setQueryTimeout(2);
                    statement.setString(1, s.world);
                    statement.setString(2, s.username);
                    statement.setString(3, s.steamid);
                    statement.setInt(4, s.playerIndex);
                    statement.setString(5, s.name);
                    statement.setFloat(6, s.x);
                    statement.setFloat(7, s.y);
                    statement.setFloat(8, s.z);
                    statement.setInt(9, s.worldVersion);
                    statement.setBoolean(10, s.dead);
                    statement.setString(11, s.splitUsername);
                    statement.setBytes(12, s.data);
                    ApocBRPayloadProvenance.bind(statement,13,s.origin);
                    if (id != null) statement.setInt(16, id);
                    if (statement.executeUpdate() != 1) throw new SQLException("Unexpected affected rows", "P0001");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (!queue.isEmpty()) log.accept("shutdown with uncommitted snapshots=" + queue.size());
        if (datasource instanceof AutoCloseable resource) {
            try { resource.close(); } catch (Exception failure) { log.accept("POOL_CLOSE_FAILURE"); }
        }
    }
}
