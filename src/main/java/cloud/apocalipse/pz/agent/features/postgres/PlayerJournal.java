package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/** Ordered player WAL and transactional receipt owner. No native objects cross this boundary. */
public final class PlayerJournal {
    public interface ReplayOperation extends PersistenceJournal.Operation { void apply() throws SQLException; }
    private static final ThreadLocal<UUID> OPERATION = new ThreadLocal<>();
    private static volatile PlayerJournal instance;
    private final PersistenceJournal journal;
    private final Thread worker;
    private volatile boolean stopping, reachable;
    private PlayerJournal(Path directory) throws IOException {
        journal = new PersistenceJournal(directory, 64L * 1024 * 1024, PlayerJournal::log);
        worker = new Thread(this::run, "ApocBR-Player-Journal"); worker.setDaemon(true); worker.start();
    }
    public static synchronized void start(Path directory) {
        if (instance != null) return;
        try { instance = new PlayerJournal(directory); }
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
        long retryLog = 0;
        while (!stopping) {
            try {
                var entry = journal.head();
                if (entry == null) {
                    try (var connection = PostgresRuntime.borrowBackground(); var statement = connection.prepareStatement("SELECT operation_id FROM public.apocbr_persistence_receipt WHERE false")) {
                        statement.setQueryTimeout(2); try (var ignored = statement.executeQuery()) { } connection.rollback();
                    }
                    reachable = true; Thread.sleep(250); continue;
                }
                if (!(entry.operation() instanceof ReplayOperation operation)) throw new SQLException("Unknown player journal operation", "0A000");
                OPERATION.set(entry.id());
                try { operation.apply(); } finally { OPERATION.remove(); }
                journal.acknowledgeCommitted(entry.id());
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            catch (Exception | LinkageError failure) {
                reachable = false;
                long now = System.nanoTime();
                if (now - retryLog > 30_000_000_000L) { retryLog = now; log("Replay pending; records preserved; issue=" + (failure instanceof SQLException sql ? sql.getSQLState() : failure.getClass().getSimpleName())); }
                try { Thread.sleep(500); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
        }
        reachable = false;
    }
    public static void drain() {
        var current = instance; if (current == null) return;
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (current.journal.status().durable() != 0 && System.nanoTime() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("Player drain interrupted"); }
        }
        if (current.journal.status().durable() != 0) throw new IllegalStateException("Player writes remain durable in the journal; replay required");
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
