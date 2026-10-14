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

/** Testing mirror only. Called by ServerPlayersVehicles, never the simulation thread. */
public final class ApocBRPostgresConnector {
    private static volatile ApocBRPostgresConnector instance;
    private static boolean configured;
    private static final String INSERT = """
        INSERT INTO public.networkplayers
        (world, username, steamid, playerindex, name, x, y, z, worldversion, isdead, splitusername, data)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String UPDATE = """
        UPDATE public.networkplayers SET world=?, username=?, steamid=?, playerindex=?,
        name=?, x=?, y=?, z=?, worldversion=?, isdead=?, splitusername=?, data=? WHERE id=?
        """;
    private static final String SELECT_USERNAME = "SELECT id FROM public.networkplayers WHERE username=? AND world=? AND playerindex=?";
    private static final String SELECT_STEAM = "SELECT id FROM public.networkplayers WHERE steamid=? AND world=? AND playerindex=?";
    private static final String WRITE_LOCK = "LOCK TABLE public.networkplayers IN SHARE ROW EXCLUSIVE MODE";

    public record Snapshot(String world, String username, String steamid, int playerIndex,
                           String name, float x, float y, float z, int worldVersion,
                           boolean dead, String splitUsername, boolean steamCoop,
                           long capturedAtMillis, byte[] data) {
        public Snapshot {
            if (world == null || world.isBlank() || username == null || username.isBlank()
                    || playerIndex < 0 || playerIndex > 3 || data == null || data.length == 0
                    || data.length > 1_048_576 || (steamCoop && (steamid == null || steamid.isBlank()))) {
                throw new IllegalArgumentException("Invalid character snapshot");
            }
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
    }

    public record Status(int pending, long pendingBytes, long committed, long failures,
                         long rejected, String lastError, boolean closed) {}

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

    private static ApocBRPostgresConnector current() {
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
            pg.setApplicationName("ApocBR-player-mirror");
            HikariConfig config = new HikariConfig();
            config.setDataSource(pg);
            config.setPoolName("ApocBR-player-mirror");
            config.setMaximumPoolSize(size);
            config.setMinimumIdle(Math.min(2, size));
            config.setConnectionTimeout(2_000);
            config.setValidationTimeout(1_000);
            config.setInitializationFailTimeout(-1);
            config.setAutoCommit(false);
            config.setMaxLifetime(1_800_000);
            instance = new ApocBRPostgresConnector(new HikariDataSource(config),
                    code -> DebugLog.log("[ApocBR][Postgres] " + code));
            instance.log.accept("mirror enabled; pool maximum=" + size);
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
            target.offer(new Snapshot(world, username, steamid, index, name, x, y, z,
                    version, dead, splitUsername, steamCoop, capturedAtMillis, data));
        } catch (IllegalArgumentException invalid) {
            target.reject("INVALID_SNAPSHOT");
        }
    }

    public static void process() {
        ApocBRPostgresConnector target = current();
        if (target != null) target.drain();
    }

    /** Safe observation only; does not initialize the pool or make SQL calls. */
    public static Status mirrorStatus() {
        return instance == null ? new Status(0, 0, 0, 0, 0, "DISABLED_OR_UNCONFIGURED", false) : instance.status();
    }

    /** Invoked on the existing worker as it terminates. Bounded best-effort final flush. */
    public static void shutdown() {
        if (instance == null) return;
        instance.nextAttemptNanos = 0; // One final attempt even if the normal retry delay has not expired.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (instance.status().pending() > 0 && System.nanoTime() < deadline) {
            instance.drain(1);
            if (instance.status().lastError().equals("SQL_FAILURE")) break;
        }
        instance.close();
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

    static String sqlDiagnostic(Throwable failure) {
        StringBuilder result = new StringBuilder("exception=" + failure.getClass().getSimpleName());
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
        if (failure instanceof SQLException sql) {
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

    private void write(Snapshot s) throws SQLException {
        try (Connection connection = datasource.getConnection()) {
            if (!connectionObserved) {
                connectionObserved = true;
                log.accept("connection acquired; PostgreSQL reachable");
            }
            connection.setAutoCommit(false);
            try {
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
                if (id != null) statement.setInt(13, id);
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
