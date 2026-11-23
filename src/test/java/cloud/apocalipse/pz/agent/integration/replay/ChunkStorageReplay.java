package cloud.apocalipse.pz.agent.integration.replay;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standalone, disposable PostgreSQL comparison using serialized chunks from an integration run. */
public final class ChunkStorageReplay {
    private static final int ROUNDS = 5;
    private static final int BATCH = 64;

    private record Key(int x, int y) {}
    private record Chunk(Key key, Path file, byte[] bytes) {}
    private record Sample(double totalMs, double requestP95Ms) {}
    @FunctionalInterface private interface ReadRound { Sample run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path map = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/postgres?prepareThreshold=-1";
        List<Chunk> chunks = new ArrayList<>();
        try (var files = Files.walk(map, 2)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Path name = map.relativize(file);
                if (name.getNameCount() != 2 || !name.toString().matches("-?\\d+[/\\\\]-?\\d+\\.bin")) continue;
                String y = name.getFileName().toString();
                var key = new Key(Integer.parseInt(name.getName(0).toString()),
                    Integer.parseInt(y.substring(0, y.length() - 4)));
                chunks.add(new Chunk(key, file, Files.readAllBytes(file)));
            }
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("No map chunk files in " + map);
        Map<Key, byte[]> expected = new HashMap<>();
        int maxBytes = 0;
        long totalBytes = 0;
        for (Chunk chunk : chunks) {
            expected.put(chunk.key, chunk.bytes);
            maxBytes = Math.max(maxBytes, chunk.bytes.length);
            totalBytes += chunk.bytes.length;
        }
        System.out.printf(Locale.ROOT, "chunks=%d totalBytes=%d maxBytes=%d%n", chunks.size(), totalBytes, maxBytes);
        try (Connection migration = DriverManager.getConnection(url, "benchmark_migrator", "")) {
            try (var statement = migration.createStatement()) {
                statement.execute("CREATE ROLE benchmark_reader LOGIN");
                statement.execute("CREATE TABLE chunk_replay (wx integer, wy integer, payload bytea NOT NULL, PRIMARY KEY(wx, wy))");
                statement.execute("GRANT SELECT ON chunk_replay TO benchmark_reader");
                statement.execute("CREATE EXTENSION pg_prewarm");
            }
            migration.setAutoCommit(false);
            try (var insert = migration.prepareStatement("INSERT INTO chunk_replay VALUES (?, ?, ?)")) {
                for (int i = 0; i < chunks.size(); i++) {
                    Chunk chunk = chunks.get(i);
                    insert.setInt(1, chunk.key.x);
                    insert.setInt(2, chunk.key.y);
                    insert.setBytes(3, chunk.bytes);
                    insert.addBatch();
                    if ((i + 1) % BATCH == 0) insert.executeBatch();
                }
                insert.executeBatch();
            }
            migration.commit();
            try (var statement = migration.createStatement()) {
                // Warm the heap, primary index and TOAST heap/index if this table has one.
                statement.executeQuery("""
                    WITH relations AS (
                        SELECT oid FROM pg_class WHERE oid = 'chunk_replay'::regclass
                        UNION SELECT reltoastrelid FROM pg_class
                            WHERE oid = 'chunk_replay'::regclass AND reltoastrelid <> 0
                    )
                    SELECT pg_prewarm(oid::regclass) FROM (
                        SELECT oid FROM relations UNION SELECT indexrelid FROM pg_index JOIN relations ON indrelid = oid
                    ) warmed
                    """).close();
            }
        }
        final byte[] reusable = new byte[maxBytes];
        try (Connection reader = DriverManager.getConnection(url, "benchmark_reader", "");
             PreparedStatement single = select(reader, 1);
             PreparedStatement batch = select(reader, BATCH);
             PreparedStatement tail = select(reader, Math.max(1, chunks.size() % BATCH))) {
            if (!reader.isValid(5)) throw new IllegalStateException("Replay reader failed validation");
            measure("file-vanilla-shape", () -> fileRound(chunks, reusable));
            var buffer = ByteBuffer.wrap(reusable);
            measure("file-channel", () -> channelRound(chunks, buffer));
            measure("postgres-single", () -> databaseRound(chunks, expected, 1, single, single));
            measure("postgres-batch64", () -> databaseRound(chunks, expected, BATCH, batch, tail));
        }
    }

    private static PreparedStatement select(Connection reader, int count) throws Exception {
        return reader.prepareStatement("SELECT wx, wy, payload FROM chunk_replay WHERE (wx, wy) IN ("
            + String.join(",", Collections.nCopies(count, "(?,?)")) + ")");
    }

    private static Sample fileRound(List<Chunk> chunks, byte[] reusable) throws Exception {
        long[] latencies = new long[chunks.size()];
        long started = System.nanoTime();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            long request = System.nanoTime();
            int len;
            try (var input = new FileInputStream(chunk.file.toFile())) {
                long expectedLength = chunk.file.toFile().length();
                len = input.read(reusable);
                if (expectedLength != len) throw new IllegalStateException("Short replay read " + chunk.file);
            }
            if (!Arrays.equals(chunk.bytes, 0, chunk.bytes.length, reusable, 0, len)) {
                throw new IllegalStateException("Changed replay file " + chunk.file);
            }
            latencies[i] = System.nanoTime() - request;
        }
        return sample(started, latencies);
    }

    private static Sample channelRound(List<Chunk> chunks, ByteBuffer buffer) throws Exception {
        long[] latencies = new long[chunks.size()];
        long started = System.nanoTime();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            long request = System.nanoTime();
            try (var channel = FileChannel.open(chunk.file)) {
                buffer.clear();
                int len = channel.read(buffer);
                if (!Arrays.equals(chunk.bytes, 0, chunk.bytes.length, buffer.array(), 0, len)) {
                    throw new IllegalStateException("Changed replay file " + chunk.file);
                }
            }
            latencies[i] = System.nanoTime() - request;
        }
        return sample(started, latencies);
    }

    private static Sample databaseRound(List<Chunk> chunks, Map<Key, byte[]> expected, int width,
                                        PreparedStatement full, PreparedStatement tail) throws Exception {
        long[] latencies = new long[(chunks.size() + width - 1) / width];
        long started = System.nanoTime();
        for (int start = 0; start < chunks.size(); start += width) {
            int count = Math.min(width, chunks.size() - start);
            var query = count == width ? full : tail;
            long request = System.nanoTime();
            for (int i = 0; i < count; i++) {
                Key key = chunks.get(start + i).key;
                query.setInt(i * 2 + 1, key.x);
                query.setInt(i * 2 + 2, key.y);
            }
            int rows = 0;
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    var key = new Key(result.getInt(1), result.getInt(2));
                    if (!Arrays.equals(expected.get(key), result.getBytes(3))) {
                        throw new IllegalStateException("Changed replay bytes for " + key);
                    }
                    rows++;
                }
            }
            if (rows != count) throw new IllegalStateException("Missing replay chunks");
            latencies[start / width] = System.nanoTime() - request;
        }
        return sample(started, latencies);
    }

    private static Sample sample(long started, long[] latencies) {
        double total = (System.nanoTime() - started) / 1_000_000.0;
        Arrays.sort(latencies);
        return new Sample(total, latencies[(int) Math.ceil(latencies.length * .95) - 1] / 1_000_000.0);
    }

    private static void measure(String mode, ReadRound action) throws Exception {
        action.run();
        var totals = new double[ROUNDS];
        var tails = new double[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            Sample sample = action.run();
            totals[i] = sample.totalMs;
            tails[i] = sample.requestP95Ms;
            System.out.printf(Locale.ROOT, "%s,round=%d,totalMs=%.3f,requestP95Ms=%.3f%n",
                mode, i + 1, sample.totalMs, sample.requestP95Ms);
        }
        Arrays.sort(totals);
        Arrays.sort(tails);
        System.out.printf(Locale.ROOT, "%s,medianTotalMs=%.3f,medianRequestP95Ms=%.3f%n",
            mode, totals[ROUNDS / 2], tails[ROUNDS / 2]);
    }
}
