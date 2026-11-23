package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One deadline and failure marker shared by nested account/login operations on one thread. */
public final class ForegroundDatabase implements AutoCloseable {
    private static final ThreadLocal<Workflow> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> GRACEFUL_QUIT_DEPTH = new ThreadLocal<>();
    private static final int QUIT_WORKFLOW_MILLIS = 30_000;
    private static final int QUIT_NETWORK_MILLIS = 30_000;
    private final Workflow workflow;
    private final boolean owner;
    private final int depth;
    private boolean closed;

    public static final class GracefulQuit implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final int depth;
        private boolean closed;

        private GracefulQuit() {
            Integer previous = GRACEFUL_QUIT_DEPTH.get();
            depth = previous == null ? 1 : previous + 1;
            GRACEFUL_QUIT_DEPTH.set(depth);
        }

        @Override public void close() {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Graceful PostgreSQL quit scope closed from another thread");
            }
            if (closed) return;
            if (!Integer.valueOf(depth).equals(GRACEFUL_QUIT_DEPTH.get())) {
                throw new IllegalStateException("Graceful PostgreSQL quit scopes must close in reverse order");
            }
            closed = true;
            if (depth == 1) GRACEFUL_QUIT_DEPTH.remove();
            else GRACEFUL_QUIT_DEPTH.set(depth - 1);
        }
    }

    private static final class Workflow {
        final Thread thread = Thread.currentThread();
        final LongSupplier clock;
        final long expires;
        final int networkMillis;
        final int queryTimeoutSeconds;
        SQLException failure;
        boolean closed;
        int depth;

        Workflow(int millis, int networkMillis, int queryTimeoutSeconds, LongSupplier clock) {
            this.clock = clock;
            this.expires = clock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(millis);
            this.networkMillis = networkMillis;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        void checkOwner() {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Foreground JDBC workflow accessed from another thread");
            }
        }
    }

    private ForegroundDatabase(int millis, int networkMillis, int queryTimeoutSeconds, LongSupplier clock) {
        Workflow previous = CURRENT.get();
        owner = previous == null;
        workflow = owner ? new Workflow(millis, networkMillis, queryTimeoutSeconds, clock) : previous;
        depth = ++workflow.depth;
        if (owner) CURRENT.set(workflow);
    }

    public static ForegroundDatabase begin() {
        boolean quitting = GRACEFUL_QUIT_DEPTH.get() != null;
        return new ForegroundDatabase(quitting ? QUIT_WORKFLOW_MILLIS : 500,
            quitting ? QUIT_NETWORK_MILLIS : 250, quitting ? 30 : 2, System::nanoTime);
    }

    public static ForegroundDatabase beginLogin(PostgresSettings settings) {
        return new ForegroundDatabase(settings.loginWorkflowMillis(), settings.loginNetworkMillis(), 2, System::nanoTime);
    }

    static ForegroundDatabase beginForTest(int millis, int networkMillis, LongSupplier clock) {
        return new ForegroundDatabase(millis, networkMillis, 2, clock);
    }

    public static GracefulQuit gracefulQuit() { return new GracefulQuit(); }

    public static void verifySuccess() throws SQLException {
        Workflow workflow = CURRENT.get();
        if (workflow != null && workflow.failure != null) throw workflow.failure;
    }
    static boolean active() { return CURRENT.get()!=null; }
    /** Bounds waiting on shared dictionary preparation as well as JDBC work in a login workflow. */
    public static int remainingWorkflowMillis() throws SQLException {
        var workflow=CURRENT.get();return workflow==null?Integer.MAX_VALUE:remaining(workflow,Integer.MAX_VALUE);
    }

    static void failed(SQLException failure) {
        Workflow workflow=CURRENT.get();
        if (workflow != null && workflow.failure == null) workflow.failure=PostgresErrors.sanitized("account operation",failure);
    }

    static int acquisitionMillis() throws SQLException {
        Workflow workflow = CURRENT.get();
        if (workflow == null) throw new SQLException("Foreground JDBC requires a workflow scope", "25000");
        // Login/bootstrap already owns a configured workflow. Do not replace its
        // acquisition budget with the ordinary 250 ms foreground-call cap.
        return remaining(workflow, workflow.networkMillis);
    }

    private static int remaining(Workflow workflow, int limit) throws SQLException {
        workflow.checkOwner();
        if (workflow.closed) throw new SQLException("Foreground database workflow is closed", "08003");
        long nanos = workflow.expires - workflow.clock.getAsLong();
        if (nanos <= 0) throw new SQLTimeoutException("Foreground database workflow deadline exceeded", "HYT00");
        return (int) Math.max(1, Math.min(limit, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    static Connection wrap(Connection connection) {
        Workflow workflow = CURRENT.get();
        if (workflow == null) throw new IllegalStateException("Foreground JDBC requires a workflow scope");
        return proxy(Connection.class, connection, connection, workflow);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object target, Connection connection, Workflow workflow) {
        return proxy(type,target,connection,workflow,"");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Object target, Connection connection, Workflow workflow, String statementSql) {
        return (T) Proxy.newProxyInstance(ForegroundDatabase.class.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                workflow.checkOwner();
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "PostgreSQL foreground " + type.getSimpleName();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new IllegalStateException("Unexpected Object method");
                    };
                }
                try {
                    String name = method.getName();
                    if ("unwrap".equals(name)) throw new SQLException("Foreground JDBC unwrapping is unavailable", "0A000");
                    if ("isWrapperFor".equals(name)) return false;
                    // Cleanup must still release resources after expiration.
                    if (!"close".equals(name) && !"isClosed".equals(name)) {
                        connection.setNetworkTimeout(Runnable::run, remaining(workflow, workflow.networkMillis));
                    }
                    if ("setString".equals(name) && AccountSqlTypes.integerParameter(statementSql,(int)arguments[0])) {
                        String value=(String)arguments[1];
                        if (value == null) ((PreparedStatement)target).setNull((int)arguments[0],java.sql.Types.INTEGER);
                        else {
                            try { ((PreparedStatement)target).setInt((int)arguments[0],Integer.parseInt(value)); }
                            catch (NumberFormatException invalid) { throw new SQLException("Invalid numeric account parameter","22018"); }
                        }
                        return null;
                    }
                    Object result = method.invoke(target, arguments);
                    if (result instanceof CallableStatement statement) {
                        try { statement.setQueryTimeout(workflow.queryTimeoutSeconds); }
                        catch (SQLException failure) { try { statement.close(); } catch (SQLException ignored) { } throw failure; }
                        return proxy(CallableStatement.class, statement, connection, workflow);
                    }
                    if (result instanceof PreparedStatement statement) {
                        try { statement.setQueryTimeout(workflow.queryTimeoutSeconds); }
                        catch (SQLException failure) { try { statement.close(); } catch (SQLException ignored) { } throw failure; }
                        String preparedSql=arguments != null && arguments.length>0 && arguments[0] instanceof String text
                            ? AccountSqlTypes.normalized(text) : statementSql;
                        return proxy(PreparedStatement.class, statement, connection, workflow,preparedSql);
                    }
                    if (result instanceof Statement statement) {
                        try { statement.setQueryTimeout(workflow.queryTimeoutSeconds); }
                        catch (SQLException failure) { try { statement.close(); } catch (SQLException ignored) { } throw failure; }
                        return proxy(Statement.class, statement, connection, workflow);
                    }
                    if (result instanceof ResultSet rows) return proxy(ResultSet.class, rows, connection, workflow);
                    if (result instanceof DatabaseMetaData metadata) return proxy(DatabaseMetaData.class, metadata, connection, workflow);
                    // JDBC back references must not leak the unbounded physical connection.
                    if (result instanceof Connection) return proxy(Connection.class, connection, connection, workflow);
                    return result;
                } catch (InvocationTargetException wrapper) {
                    if (wrapper.getCause() instanceof SQLException sql) {
                        SQLException safe = PostgresErrors.sanitized("foreground operation", sql);
                        if (workflow.failure == null) workflow.failure = safe;
                        throw safe;
                    }
                    throw wrapper.getCause();
                } catch (SQLException failure) {
                    SQLException safe = PostgresErrors.sanitized("foreground operation", failure);
                    if (workflow.failure == null) workflow.failure = safe;
                    throw safe;
                }
            });
    }

    @Override
    public void close() {
        workflow.checkOwner();
        if (closed) return;
        if (CURRENT.get() != workflow || workflow.depth != depth) {
            throw new IllegalStateException("Foreground database scopes must close in reverse order");
        }
        closed = true;
        workflow.depth--;
        if (owner) {
            workflow.closed = true;
            CURRENT.remove();
        }
    }
}
