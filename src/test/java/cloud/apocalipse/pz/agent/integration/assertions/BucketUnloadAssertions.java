package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

final class BucketUnloadAssertions {
    private record Key(int cycle, int index, String kind) {}
    private record Row(Key key, long visits, long appends, long removed, long mutationNanos,
                       long nativeCalls, long comparisons, long nativeNanos) {}
    private record Totals(long visits, long removed, long comparisons, long mutation, long search) {}

    private BucketUnloadAssertions() {}

    static void verify(Path directory) throws IOException {
        assertTrue(Files.isRegularFile(directory.resolve("large-bucket-order-oracle")),
            "Expected the real bucket swap, append and removal reference check");
        List<Row> rows = buckets(directory);
        var fallbacks = new HashMap<Key, Long>();
        for (String row : read(directory, "unload-bucket-fallbacks.csv")) {
            String[] fields = row.split(",");
            fallbacks.merge(key(fields), number(fields, 4), Long::sum);
        }
        for (Row row : rows) {
            assertEquals(row.nativeCalls, fallbacks.getOrDefault(row.key, 0L), "Actual native searches for " + row.key);
            assertTrue(row.visits >= row.appends + row.removed, "Bucket mutations must come from native visits");
            assertTrue(row.mutationNanos >= row.nativeNanos, "Native lookup is inside the bucket removal call");
            fallbacks.remove(row.key);
        }
        assertTrue(fallbacks.isEmpty(), "Unexpected fallback without bucket visits");
        long totalNative = read(directory, "unload-sampling-windows.csv").stream()
            .mapToLong(row -> number(row.split(","), 8)).sum();
        long classified = rows.stream().mapToLong(Row::comparisons).sum();
        assertTrue(classified <= totalNative, "Bucket probe must not count native comparisons twice");
        assertTrue(rows.stream().mapToLong(Row::removed).sum() > 0, "Expected actual bucket removals");

        boolean enabled = AgentConfiguration.entityBucketRemovalIndexEnabled();
        var phases = read(directory, "unload-entity-phases.csv");
        for (String expected : List.of("fanout", "unregister", "objectEntityId")) {
            assertTrue(phases.stream().anyMatch(row -> row.split(",")[1].equals(expected)), "Missing " + expected);
        }
        boolean built = phases.stream().anyMatch(row -> row.split(",")[1].equals("positionBuild"));
        assertEquals(enabled, built, "Expected a lazy position build only in the enabled fixture");
        String baseline = System.getProperty("pz.it.streamingBaseline");
        if (baseline != null) {
            assertTrue(enabled, "Pass the disabled run as the baseline for the enabled treatment");
            Path previous = Path.of(baseline);
            var before = runtime(previous);
            var after = runtime(directory);
            for (String key : List.of("agent.sha256", "game.sha256", "streaming.fixture", "streaming.seed")) {
                assertEquals(before.getProperty(key), after.getProperty(key), "A/B must match " + key);
            }
            compare(totals(buckets(previous)), totals(rows));
        }
    }

    private static void compare(Totals previous, Totals current) {
        assertTrue(previous.comparisons > 0 && previous.removed > 0 && current.removed > 0);
        assertTrue(Math.abs(current.removed - previous.removed) <= previous.removed / 20 + 1,
            "Warm bucket removal workload must be comparable");
        double before = (double) previous.comparisons / previous.removed;
        double after = (double) current.comparisons / current.removed;
        System.out.printf(Locale.ROOT,
            "[Server-IT] Bucket A/B warm visits %d -> %d, removals %d -> %d, native comparisons %d -> %d (%.1f -> %.1f per removal), mutation %.3f -> %.3f ms, native search %.3f -> %.3f ms%n",
            previous.visits, current.visits, previous.removed, current.removed,
            previous.comparisons, current.comparisons, before, after, previous.mutation / 1e6, current.mutation / 1e6,
            previous.search / 1e6, current.search / 1e6);
        assertTrue(after <= before / 2, "Expected to eliminate at least half the native comparisons per warm removal");
    }

    private static Totals totals(List<Row> all) {
        var rows = all.stream().filter(row -> row.key.cycle > 0).toList();
        return new Totals(rows.stream().mapToLong(Row::visits).sum(), rows.stream().mapToLong(Row::removed).sum(),
            rows.stream().mapToLong(Row::comparisons).sum(), rows.stream().mapToLong(Row::mutationNanos).sum(),
            rows.stream().mapToLong(Row::nativeNanos).sum());
    }

    private static List<Row> buckets(Path directory) throws IOException {
        return read(directory, "unload-buckets.csv").stream().map(row -> {
            String[] fields = row.split(",");
            return new Row(key(fields), number(fields, 3), number(fields, 4), number(fields, 7),
                number(fields, 5) + number(fields, 8), number(fields, 10), number(fields, 11), number(fields, 12));
        }).toList();
    }

    private static Key key(String[] fields) { return new Key(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), fields[2]); }
    private static long number(String[] fields, int index) { return Long.parseLong(fields[index]); }

    private static List<String> read(Path directory, String file) throws IOException {
        var lines = Files.readAllLines(directory.resolve(file));
        assertFalse(lines.isEmpty(), "Missing CSV header in " + file);
        return lines.subList(1, lines.size());
    }

    private static Properties runtime(Path directory) throws IOException {
        var props = new Properties();
        try (var input = Files.newInputStream(directory.resolve("runtime.properties"))) { props.load(input); }
        return props;
    }
}
