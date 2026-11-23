package cloud.apocalipse.pz.agent.experimental.chunks;

import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Writes detached, revisioned observations. Native map saves remain the recovery source. */
final class ChunkObjectMutationWriter implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 256;
    private static final int BATCH_SIZE = 64;
    private static final long BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private static final String UPSERT = """
        INSERT INTO apocbr_world.chunk_object_mutations AS previous
            (dataset, run_id, object_id, revision, x, y, z, object_index, special_object, payload)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (dataset, run_id, object_id) DO UPDATE SET
            revision = EXCLUDED.revision, x = EXCLUDED.x, y = EXCLUDED.y, z = EXCLUDED.z,
            object_index = EXCLUDED.object_index, special_object = EXCLUDED.special_object,
            payload = EXCLUDED.payload
        WHERE previous.revision < EXCLUDED.revision
        """;

    record Location(int x, int y, int z, int index, boolean special) {}
    record Change(UUID objectId, long revision, Location location, byte[] payload) {}

    private final String dataset;
    private final UUID runId = UUID.randomUUID();
    private final ArrayBlockingQueue<Change> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean closing;
    private volatile int inFlight;
    private long committed;

    ChunkObjectMutationWriter(String dataset) {
        this.dataset = dataset;
        worker = Thread.ofPlatform().daemon().name("ApocBR-chunk-object-writes").unstarted(this::run);
    }

    void start() {
        worker.start();
    }

    void offer(Change change) {
        if (closing || !queue.offer(change)) dropped.incrementAndGet();
    }

    void recordGap() {
        dropped.incrementAndGet();
    }

    private void run() {
        List<Change> batch = new ArrayList<>(BATCH_SIZE);
        long nextFailureLog = 0;
        try {
            while (!closing || !queue.isEmpty() || !batch.isEmpty()) {
                if (batch.isEmpty()) {
                    Change first = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    batch.add(first);
                    inFlight = batch.size();
                    long deadline = System.nanoTime() + BATCH_NANOS;
                    while (batch.size() < BATCH_SIZE) {
                        long remaining = deadline - System.nanoTime();
                        Change next = closing || remaining <= 0
                            ? queue.poll() : queue.poll(remaining, TimeUnit.NANOSECONDS);
                        if (next == null) break;
                        batch.add(next);
                        inFlight = batch.size();
                    }
                }
                try {
                    write(batch);
                    committed += batch.size();
                    batch.clear();
                    inFlight = 0;
                } catch (SQLException | RuntimeException failure) {
                    long now = System.nanoTime();
                    if (nextFailureLog == 0 || now - nextFailureLog >= 0) {
                        String state = failure instanceof SQLException sql ? sql.getSQLState() : null;
                        System.err.println("[ApocBR-Agent][ChunkObjects] Write retry; SQLState="
                            + (state != null && state.matches("[A-Z0-9]{5}") ? state : "HY000"));
                        nextFailureLog = now + TimeUnit.SECONDS.toNanos(5);
                    }
                    Thread.sleep(250);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            System.out.println("[ApocBR-Agent][ChunkObjects] committed=" + committed
                + " dropped=" + dropped.get() + " uncommitted=" + (inFlight + queue.size()));
        }
    }

    private void write(List<Change> batch) throws SQLException {
        try (var connection = PostgresRuntime.borrowBackground();
             var query = connection.prepareStatement(UPSERT)) {
            for (Change change : batch) {
                Location location = change.location();
                query.setString(1, dataset);
                query.setObject(2, runId);
                query.setObject(3, change.objectId());
                query.setLong(4, change.revision());
                query.setInt(5, location.x());
                query.setInt(6, location.y());
                query.setInt(7, location.z());
                query.setInt(8, location.index());
                query.setBoolean(9, location.special());
                query.setBytes(10, change.payload());
                query.addBatch();
            }
            query.executeBatch();
            connection.commit();
        }
    }

    @Override public void close() {
        closing = true;
        try {
            if (!worker.join(java.time.Duration.ofSeconds(5))) {
                worker.interrupt();
                if (!worker.join(java.time.Duration.ofSeconds(5))) {
                    System.err.println("[ApocBR-Agent][ChunkObjects] Worker did not stop within the shutdown budget");
                }
            }
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            Thread.currentThread().interrupt();
        }
    }
}
