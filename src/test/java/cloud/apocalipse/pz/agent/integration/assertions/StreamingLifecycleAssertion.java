package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class StreamingLifecycleAssertion implements ServerAssertion {
    @Override public String name() { return "Playerless streaming loads, unloads, and drains saves"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        assumeTrue(Boolean.getBoolean("pz.it.streaming"), "Enable with -Dpz.it.streaming=true");
        Path directory = server.directory();
        Path complete = directory.resolve("streaming-complete");
        Path error = directory.resolve("streaming-error.txt");
        Files.createFile(directory.resolve("streaming-request"));
        server.await("playerless streaming cycles", Duration.ofSeconds(200),
            () -> Files.isRegularFile(complete) || Files.isRegularFile(error));
        if (Files.isRegularFile(error)) fail(Files.readString(error));
        Map<String, List<Sample>> results = readSamples(directory);
        String baselinePath = System.getProperty("pz.it.streamingBaseline");
        Map<String, List<Sample>> baseline = Map.of();
        if (baselinePath != null) {
            Path previous = Path.of(baselinePath);
            for (String key : List.of("game.sha256", "streaming.fixture", "streaming.seed")) {
                assertEquals(runtime(previous).getProperty(key), runtime(directory).getProperty(key),
                    "Streaming baseline must match " + key);
            }
            baseline = readSamples(previous);
            assertEquals(baseline.keySet(), results.keySet(), "Comparable streaming phases");
        }
        for (var entry : results.entrySet()) {
            String phase = entry.getKey();
            var samples = entry.getValue();
            double median = medianMs(samples);
            long max = samples.stream().mapToLong(Sample::max).max().orElseThrow();
            String comparison = "";
            if (!baseline.isEmpty()) {
                var before = baseline.get(phase);
                assertEquals(before.stream().map(Sample::chunks).toList(), samples.stream().map(Sample::chunks).toList(),
                    "Comparable chunk workload for " + phase);
                double old = medianMs(before);
                comparison = String.format(Locale.ROOT, " baseline=%.3f ms, delta=%+.3f ms", old, median - old);
            }
            System.out.printf(Locale.ROOT, "[Server-IT] Streaming %-24s median=%.3f ms/cycle, max=%.3f ms/call%s%n",
                phase, median, max / 1_000_000.0, comparison);
        }
    }

    private record Sample(int cycle, int chunks, long total, long max) {}

    private static Map<String, List<Sample>> readSamples(Path directory) throws Exception {
        assertEquals("6", Files.readString(directory.resolve("streaming-complete")), "Completed cycle count");
        List<String> lines = Files.readAllLines(directory.resolve("streaming.csv"));
        assertEquals("cycle,warmup,cells,chunks,phase,calls,totalNanos,maxNanos", lines.getFirst());
        Map<String, List<Sample>> samples = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",");
            if (Boolean.parseBoolean(fields[1])) continue;
            assertEquals(4, Integer.parseInt(fields[2]));
            assertTrue(Integer.parseInt(fields[3]) > 0);
            assertTrue(Long.parseLong(fields[5]) > 0);
            long total = Long.parseLong(fields[6]);
            long max = Long.parseLong(fields[7]);
            assertTrue(total >= max && max > 0, line);
            samples.computeIfAbsent(fields[4], key -> new ArrayList<>())
                .add(new Sample(Integer.parseInt(fields[0]), Integer.parseInt(fields[3]), total, max));
        }
        assertTrue(samples.keySet().containsAll(List.of("map.preupdate", "map.postupdate", "map.relevance",
            "cell.loadQueueToRecalcQueue", "cell.recalcQueueToIntegration",
            "cell.integrate", "cell.unload", "chunk.detach", "chunk.saveWorker",
            "wall.requestToLoaded", "wall.releaseToDetached", "wall.releaseToSaveDrained")));
        for (var entries : samples.values()) {
            assertEquals(List.of(1, 2, 3, 4, 5), entries.stream().map(Sample::cycle).toList());
        }
        return samples;
    }

    private static Properties runtime(Path directory) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(directory.resolve("runtime.properties"))) { properties.load(in); }
        return properties;
    }

    private static double medianMs(List<Sample> samples) {
        return samples.stream().mapToLong(Sample::total).sorted().toArray()[samples.size() / 2] / 1_000_000.0;
    }
}
