package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/** Only complete exact views become visible. JDBC runs on the caller's I/O thread. */
public final class DictionaryCache {
    private static final ConcurrentHashMap<DictionaryOrigin, CompletableFuture<View>> VIEWS = new ConcurrentHashMap<>();
    private static int entries;
    private DictionaryCache() { }

    public record View(DictionarySnapshot snapshot, Map<String, DictionarySnapshot.Entry> identities) {
        View(DictionarySnapshot snapshot) {
            this(snapshot, index(snapshot));
        }
        private static Map<String, DictionarySnapshot.Entry> index(DictionarySnapshot snapshot) {
            Map<String, DictionarySnapshot.Entry> result = new HashMap<>();
            for (var entry : snapshot.entries()) result.put(entry.identity(), entry);
            return Map.copyOf(result);
        }
        public DictionarySnapshot.Entry entry(String family, String register, int id) {
            var entry = identities.get(family + ":" + register + ":" + id);
            if (entry == null) throw new IllegalStateException("Source dictionary identity unavailable");
            return entry;
        }
    }

    private static synchronized void complete(DictionaryOrigin origin, CompletableFuture<View> future, DictionarySnapshot snapshot) {
        if (VIEWS.get(origin) != future) throw new IllegalStateException("Dictionary preparation cancelled");
        if (future.isDone()) return;
        if (entries + snapshot.entries().size() > 1_000_000) throw new IllegalStateException("Dictionary cache entry budget exceeded");
        View view = new View(snapshot);
        entries += snapshot.entries().size();
        future.complete(view);
    }

    private static synchronized CompletableFuture<View> reserve(DictionaryOrigin origin, CompletableFuture<View> candidate) {
        var existing = VIEWS.get(origin);
        if (existing != null) return existing;
        if (VIEWS.size() >= 256) throw new IllegalStateException("Dictionary cache view budget exceeded");
        VIEWS.put(origin, candidate);
        return candidate;
    }

    static void install(DictionaryOrigin origin, DictionarySnapshot snapshot) {
        var candidate = new CompletableFuture<View>();
        var future = reserve(origin, candidate);
        try { complete(origin, future, snapshot); }
        catch (RuntimeException failure) { future.completeExceptionally(failure); VIEWS.remove(origin, future); throw failure; }
    }

    public static void prepare(DictionaryOrigin origin) throws SQLException {
        prepare(origin, new DictionaryDatabase());
    }

    static void prepare(DictionaryOrigin origin, DictionaryDatabase database) throws SQLException {
        var candidate = new CompletableFuture<View>();
        CompletableFuture<View> future;
        try { future = reserve(origin, candidate); }
        catch (RuntimeException failure) { throw new SQLException("Dictionary cache capacity unavailable", "54000"); }
        if (future == candidate) {
            try { complete(origin, future, database.load(origin)); }
            catch (SQLException | RuntimeException failure) {
                if (future.completeExceptionally(failure)) VIEWS.remove(origin, future);
            }
        }
        try { future.get(cloud.apocalipse.pz.agent.features.postgres.ForegroundDatabase.remainingWorkflowMillis(),TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) { throw new java.sql.SQLTimeoutException("Source dictionary preparation deadline exceeded","HYT00"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt();throw new SQLException("Source dictionary preparation interrupted","57014"); }
        catch (ExecutionException failure) {
            String state = failure.getCause() instanceof SQLException sql ? sql.getSQLState() : "55000";
            throw new SQLException("Source dictionary preparation failed", state != null && state.matches("[A-Z0-9]{5}") ? state : "55000");
        }
    }

    /** Decoder-facing lookup never opens a database connection or waits for preparation. */
    public static View ready(DictionaryOrigin origin) {
        var future = VIEWS.get(origin);
        if (future == null || !future.isDone() || future.isCompletedExceptionally())
            throw new IllegalStateException("Source dictionary is not prepared");
        return future.getNow(null);
    }

    static synchronized void clear() {
        for (var future : VIEWS.values()) future.completeExceptionally(new IllegalStateException("Dictionary cache closed"));
        VIEWS.clear(); entries = 0;
    }
}
