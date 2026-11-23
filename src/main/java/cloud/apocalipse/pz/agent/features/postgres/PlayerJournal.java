package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;

/** Ordered player WAL and transactional receipt owner. No native objects cross this boundary. */
public final class PlayerJournal {
    public interface ReplayOperation extends PersistenceJournal.Operation { void apply() throws SQLException; }
    public static final class StaleLife extends SQLException {
        public StaleLife() { super("Live character life changed", "40001"); }
    }
    private static final ThreadLocal<UUID> OPERATION = new ThreadLocal<>();
    private static final long REPORT_INTERVAL_NANOS = 30_000_000_000L;
    private static volatile PlayerJournal instance;
    private final PersistenceJournal journal;
    private final Thread worker;
    private final Supplier<String> databaseMetrics;
    private volatile boolean stopping, reachable;
    private long lastReport, replayed, quarantined, failedAttempts;
    private PlayerJournal(Path directory, Supplier<String> databaseMetrics) throws IOException {
        journal = new PersistenceJournal(directory, 64L * 1024 * 1024, PlayerJournal::log);
        this.databaseMetrics = databaseMetrics;
        worker = new Thread(this::run, "ApocBR-Player-Journal"); worker.setDaemon(true); worker.start();
    }
    public static void start(Path directory) { start(directory, () -> ""); }
    public static synchronized void start(Path directory, Supplier<String> databaseMetrics) {
        if (instance != null) return;
        try { instance = new PlayerJournal(directory, databaseMetrics); }
        catch (IOException failure) { throw new IllegalStateException("Player journal unavailable; records preserved", failure); }
    }
    public static boolean journaling() { var current = instance; return current != null && !current.stopping; }
    public static UUID submit(ReplayOperation operation) {
        var current = instance;
        if (current == null || current.stopping) throw new IllegalStateException("Player journal unavailable");
        try { return current.journal.submitDurable(operation); }
        catch (IOException failure) { current.reachable = false; throw new IllegalStateException("Player capture is not durable", failure); }
    }
    public static boolean readable(String key) {
        var current = instance;
        return current != null && !current.stopping && current.reachable && !current.journal.pendingPlayer(key);
    }
    public static boolean claim(Connection connection) throws SQLException {
        UUID id = OPERATION.get(); if (id == null) throw new SQLException("Player write outside journal replay", "55000");
        try (var statement = connection.prepareStatement("INSERT INTO public.apocbr_persistence_receipt(operation_id) VALUES (?) ON CONFLICT(operation_id) DO NOTHING")) {
            statement.setQueryTimeout(2); statement.setObject(1, id); return statement.executeUpdate() != 0;
        }
    }
    private void run() {
        report("REPLAY_OPENED", null, "NONE", true);
        boolean retrying = false;
        while (!stopping) {
            PersistenceJournal.Entry entry = null;
            try {
                entry = journal.head();
                if (entry == null) {
                    try (var connection = PostgresRuntime.borrowBackground(); var statement = connection.prepareStatement("SELECT operation_id FROM public.apocbr_persistence_receipt WHERE false")) {
                        statement.setQueryTimeout(2); try (var ignored = statement.executeQuery()) { } connection.rollback();
                    }
                    boolean recovered = !reachable;
                    reachable = true;
                    retrying = false;
                    report("AVAILABLE", null, "NONE", recovered);
                    Thread.sleep(250); continue;
                }
                if (!(entry.operation() instanceof ReplayOperation operation)) throw new SQLException("Unknown player journal operation", "0A000");
                replay(entry, operation);
                retrying = false;
                report("REPLAY_PROGRESS", entry, "NONE", false);
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            catch (Exception | LinkageError failure) {
                reachable = false;
                failedAttempts++;
                report("REPLAY_PENDING", entry, issue(failure), !retrying);
                retrying = true;
                try { Thread.sleep(500); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
        }
        reachable = false;
    }

    private void replay(PersistenceJournal.Entry entry, ReplayOperation operation) throws IOException, SQLException {
        // An interrupted checkpoint may leave an already archived operation at the head.
        if (journal.quarantined(entry.id())) {
            journal.quarantine(entry.id());
            quarantined++;
            log("Replay quarantine checkpoint recovered; operation=" + entry.id() + "; kind=" + operation.kind());
            return;
        }
        OPERATION.set(entry.id());
        try {
            operation.apply();
        } catch (SQLException failure) {
            SQLException rejected = rejectedData(failure);
            if (rejected == null) throw failure;
            journal.quarantine(entry.id());
            quarantined++;
            log("Replay quarantined; operation=" + entry.id() + "; kind=" + operation.kind()
                + "; issue=" + rejected.getSQLState() + constraintDetails(rejected));
            return;
        } finally {
            OPERATION.remove();
        }
        journal.acknowledgeCommitted(entry.id());
        replayed++;
    }

    private void report(String state, PersistenceJournal.Entry entry, String issue, boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastReport < REPORT_INTERVAL_NANOS) return;
        lastReport = now;
        String operation = entry == null ? "" : "; operation=" + entry.id() + "; kind=" + entry.operation().kind();
        log("state=" + state + "; playerReadsReady=" + reachable + "; issue=" + issue + operation
            + "; replayed=" + replayed + "; quarantined=" + quarantined + "; failedAttempts=" + failedAttempts
            + backlog(journal.status()) + connectionMetrics());
    }

    private String connectionMetrics() {
        try { return PostgresRuntime.poolMetrics() + databaseMetrics.get(); }
        catch (RuntimeException | LinkageError failure) { return "; connectionMetrics=UNAVAILABLE"; }
    }

    private static String issue(Throwable failure) {
        if (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
            return sql.getSQLState();
        }
        return failure.getClass().getSimpleName();
    }

    private static String backlog(PersistenceJournal.Status status) {
        return "; staged=" + status.staged() + "; stagedBytes=" + status.stagedBytes() + "; durable=" + status.durable()
            + "; durableBytes=" + status.bytes() + "; oldestPendingMs=" + status.oldestAgeMs()
            + "; pendingPlayers=" + status.pendingPlayers();
    }

    private static SQLException rejectedData(SQLException failure) {
        SQLException rejected = null;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getNextException()) {
            String state = failure.getSQLState();
            if (failure instanceof SQLTransientConnectionException || failure instanceof SQLRecoverableException
                || state != null && (state.startsWith("08") || state.startsWith("57P"))) return null;
            if (failure instanceof StaleLife || state != null && (state.startsWith("22") || state.startsWith("23"))) {
                if (rejected == null || failure instanceof PSQLException) rejected = failure;
            }
        }
        return rejected;
    }

    private static String constraintDetails(SQLException failure) {
        if (!(failure instanceof PSQLException postgres) || postgres.getServerErrorMessage() == null) return "";
        var error = postgres.getServerErrorMessage();
        return identifier("schema", error.getSchema()) + identifier("table", error.getTable())
            + identifier("constraint", error.getConstraint());
    }

    private static String identifier(String field, String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") ? "; " + field + "=" + value : "";
    }
    public static void drain() {
        var current = instance; if (current == null) return;
        var status = current.journal.status();
        if (status.durable() == 0) return;
        log("state=DRAINING" + backlog(status));
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (status.durable() != 0 && System.nanoTime() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("Player drain interrupted"); }
            status = current.journal.status();
        }
        if (status.durable() != 0) {
            log("state=DRAIN_TIMEOUT" + backlog(status));
            throw new IllegalStateException("Player writes remain durable in the journal; replay required");
        }
        log("state=DRAIN_COMPLETE" + backlog(status));
    }
    public static synchronized void shutdown() {
        var current = instance; if (current == null) return;
        current.stopping = true; current.worker.interrupt();
        try {
            current.worker.join(15000);
            if (current.worker.isAlive()) throw new IllegalStateException("Player journal worker did not stop");
            current.journal.close(); instance = null;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("Player journal shutdown interrupted"); }
        catch (IOException failure) { throw new IllegalStateException("Player journal close failed", failure); }
    }
    private static void log(String message) { System.err.println("[ApocBR-Agent][Player-Journal] " + message); }
    private PlayerJournal() { throw new AssertionError(); }
}
