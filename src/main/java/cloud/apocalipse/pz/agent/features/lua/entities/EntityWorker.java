package cloud.apocalipse.pz.agent.features.lua.entities;

import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One FIFO lane. Each accepted request holds its reservation until Lua consumes the result. */
final class EntityWorker implements AutoCloseable {
    private static final int LIMIT = 256;
    private final ThreadPoolExecutor executor;
    private final ConcurrentLinkedQueue<Map<String, Object>> completed =
            new ConcurrentLinkedQueue<>();
    private long sequence;
    private int outstanding; // Lua owner

    EntityWorker(boolean enabled) {
        executor =
                enabled
                        ? new ThreadPoolExecutor(
                                1,
                                1,
                                0,
                                TimeUnit.SECONDS,
                                new ArrayBlockingQueue<>(LIMIT),
                                job -> {
                                    Thread thread = new Thread(job, "ApocBR-ModEntities");
                                    thread.setDaemon(true);
                                    return thread;
                                })
                        : null;
    }

    interface Operation {
        Map<String, Object> run(Connection connection) throws SQLException;
    }

    boolean available() {
        return executor != null && !executor.isShutdown();
    }

    synchronized Map<String, Object> submit(boolean mutates, Operation operation) {
        if (!available()) return Map.of("accepted", false, "status", "UNAVAILABLE");
        if (outstanding >= LIMIT) return Map.of("accepted", false, "status", "BUSY");
        String id = Long.toString(++sequence);
        executor.execute(
                () -> {
                    Map<String, Object> result = new LinkedHashMap<>(execute(mutates, operation));
                    result.put("id", id);
                    completed.add(Map.copyOf(result));
                });
        outstanding++;
        return Map.of("accepted", true, "id", id);
    }

    synchronized List<Map<String, Object>> poll() {
        if (!available()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Map<String, Object> result = completed.poll();
            if (result == null) break;
            outstanding--;
            results.add(result);
        }
        return results;
    }

    synchronized void checkIdle() {
        if (available() && outstanding != 0)
            throw new IllegalStateException("Mod entity requests still active");
    }

    private static Map<String, Object> execute(boolean mutates, Operation operation) {
        boolean committing = false;
        try (Connection connection = PostgresRuntime.borrowBackground()) {
            try {
                var result = operation.run(connection);
                checkInterrupted();
                committing = true;
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw failure;
            }
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            if (committing && mutates) return status("OUTCOME_UNKNOWN");
            if ("40001".equals(state) && mutates) return status("CONFLICT");
            if ("54000".equals(state)) return status("LIMIT_EXCEEDED");
            if (state != null && state.startsWith("42")) return status("SCHEMA_ERROR");
            return status("DATABASE_ERROR");
        } catch (RuntimeException failure) {
            return status(committing && mutates ? "OUTCOME_UNKNOWN" : "INVALID_DATA");
        }
    }

    static Map<String, Object> status(String status) {
        return Map.of("status", status);
    }

    static void checkInterrupted() throws SQLException {
        if (Thread.currentThread().isInterrupted())
            throw new SQLException("Mod entity worker stopped", "57014");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!available()) return;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                int discarded = executor.shutdownNow().size();
                if (discarded != 0)
                    System.err.println(
                            "[ApocBR-Agent] Mod entity shutdown discarded "
                                    + discarded
                                    + " queued requests");
                if (!executor.awaitTermination(6, TimeUnit.SECONDS))
                    throw new IllegalStateException("Mod entity database worker did not stop");
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mod entity database shutdown interrupted");
        } finally {
            completed.clear();
        }
    }
}
