package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.experimental.chunks.PostgresChunksModule;
import cloud.apocalipse.pz.agent.features.optimization.vehicle.ParkedVehicleModule;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.experimental.worldsimulation.WorldSimulation;
import cloud.apocalipse.pz.agent.features.optimization.streaming.ChunkAdjacencyModule;
import cloud.apocalipse.pz.agent.features.optimization.streaming.ChunkContainerModule;
import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ChunkMigrationFixture;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import cloud.apocalipse.pz.agent.integration.probe.StreamingWorkload;
import cloud.apocalipse.pz.agent.integration.probe.UnloadBreakdownProbe;
import cloud.apocalipse.pz.agent.integration.probe.UnloadSamplingProbe;
import cloud.apocalipse.pz.agent.integration.probe.BucketUnloadProbe;
import cloud.apocalipse.pz.agent.integration.probe.VehicleCadenceProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.ToLongFunction;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class StreamingLifecycleAssertion implements ServerAssertion {
    @Override public String name() { return "Playerless streaming loads, unloads, and drains saves"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        assumeTrue(!WorldSimulation.enabled(), "The experimental assertion owns the streaming fixture");
        drive(server);
    }

    public static void drive(ServerTestContext server) throws Exception {
        assumeTrue(Boolean.getBoolean("pz.it.streaming"), "Enable with -Dpz.it.streaming=true");
        var workload = StreamingWorkload.from(System.getProperties());
        Path directory = server.directory();
        Path complete = directory.resolve("streaming-complete");
        Path error = directory.resolve("streaming-error.txt");
        boolean movingScenario = Boolean.getBoolean("pz.it.movingObjects");
        Files.createFile(directory.resolve("streaming-request"));
        if (movingScenario) MovingObjectTelemetryAssertion.drive(server);
        server.await("playerless streaming cycles", Duration.ofSeconds(workload.runTimeoutSeconds()),
            () -> Files.isRegularFile(complete) || Files.isRegularFile(error));
        if (Files.isRegularFile(error)) fail(Files.readString(error));
        if (ChunkMigrationFixture.enabled()) {
            ChunkMigrationFixture.verify(directory);
            ChunkMigrationFixture.verifyObjectMutations(server);
            for (var hook : new PostgresChunksModule().hookChecks()) {
                server.assertHook(hook.targetClass(), hook.method(), hook.helperClass(), hook.helperMethod());
            }
            return;
        }
        if (Boolean.getBoolean(UnloadBreakdownProbe.PROPERTY)) UnloadBreakdownAssertions.verify(directory);
        if (Boolean.getBoolean(UnloadSamplingProbe.PROPERTY)) UnloadSamplingAssertions.verify(directory);
        if (Boolean.getBoolean(BucketUnloadProbe.PROPERTY)) BucketUnloadAssertions.verify(directory);
        if (movingScenario) return;
        if (AgentConfiguration.cellRelevanceOptimizationEnabled()) {
            assertTrue(Files.isRegularFile(directory.resolve("cell-relevance-oracle")));
            assertTrue(server.telemetrySnapshots().stream().anyMatch(json -> json.contains("\"map.cell.relevanceScansSkipped\"")));
        }
        if (AgentConfiguration.optimizationEnabled(AgentConfiguration.ENTITY_SIMULATION_OPTIMIZATION)) {
            assertTrue(Files.isRegularFile(directory.resolve("bucket-removal-oracle")));
            assertTrue(server.telemetrySnapshots().stream().anyMatch(json -> json.contains("\"entities.removal.usingPlayerBucketIndexed\"")));
        }
        if (Boolean.getBoolean(VehicleCadenceProbe.COMPARE_PROPERTY)) verifyVehicleCadence(server, workload);
        if (workload.chunkComparison()) verifyChunkLifecycle(server, workload);
        Map<String, List<Sample>> results = readSamples(directory, workload);
        String baselinePath = System.getProperty("pz.it.streamingBaseline");
        Map<String, List<Sample>> baseline = Map.of();
        if (baselinePath != null) {
            Path previous = Path.of(baselinePath);
            for (String key : List.of("game.sha256", "streaming.fixture", "streaming.seed")) {
                assertEquals(runtime(previous).getProperty(key), runtime(directory).getProperty(key),
                    "Streaming baseline must match " + key);
            }
            baseline = readSamples(previous, workload);
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

    private record ChunkSample(long chunks, long calls, long saved, long nanos) {}

    private static void verifyChunkLifecycle(ServerTestContext server, StreamingWorkload workload) throws Exception {
        for (var module : List.of(new ChunkContainerModule(), new ChunkAdjacencyModule())) {
            for (var check : module.hookChecks()) {
                server.assertHook(check.targetClass(), check.method(), check.helperClass(), check.helperMethod());
            }
        }
        assertTrue(server.telemetrySnapshots().stream().anyMatch(json -> json.contains("\"map.chunk.containerChecksSkipped\"")));
        assertTrue(server.telemetrySnapshots().stream().anyMatch(json -> json.contains("\"map.chunk.interiorUnlinksSkipped\"")));
        var lines = Files.readAllLines(server.directory().resolve("chunk-lifecycle-comparison.csv"));
        assertEquals(2 + (workload.cycles() - 1) * 4, lines.size() - 1, "Cold and warm comparison arms");
        var oracles = Files.readString(server.directory().resolve("chunk-lifecycle-oracles.txt")).split(",");
        assertTrue(Integer.parseInt(oracles[0]) >= 128, "Interior reverse links must stay intact");
        assertTrue(Integer.parseInt(oracles[1]) >= 128, "Cross-chunk reverse links must be removed");
        assertEquals(4, Integer.parseInt(oracles[2]), "Plain, regular, existing and freezer-only container checks");
        var samples = new ChunkSample[workload.cycles()][2][2];
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            int cycle = Integer.parseInt(f[0]);
            int stage = Boolean.parseBoolean(f[1]) ? 0 : 1;
            int arm = Boolean.parseBoolean(f[2]) ? 1 : 0;
            assertNull(samples[cycle][stage][arm], line);
            samples[cycle][stage][arm] = new ChunkSample(Long.parseLong(f[3]), Long.parseLong(f[4]),
                Long.parseLong(f[5]), Long.parseLong(f[6]));
        }
        for (int stage = 0; stage < 2; stage++) {
            String phase = stage == 0 ? "container checks" : "adjacency detach";
            long offCalls = 0, onCalls = 0, onSaved = 0, offNanos = 0, onNanos = 0;
            for (int cycle = 1; cycle < workload.cycles(); cycle++) {
                var off = samples[cycle][stage][0];
                var on = samples[cycle][stage][1];
                assertEquals(workload.cellsFor(cycle).size() * 32L, off.chunks(), phase);
                assertEquals(off.chunks(), on.chunks(), phase);
                assertTrue(off.calls() > 0 && on.calls() > 0 && off.nanos() > 0 && on.nanos() > 0, phase);
                assertEquals(0, off.saved(), phase);
                assertTrue(on.saved() > on.calls() / 4, phase + " must avoid work on a material share of operations");
                offCalls += off.calls();
                onCalls += on.calls();
                onSaved += on.saved();
                offNanos += off.nanos();
                onNanos += on.nanos();
            }
            assertTrue(Math.abs(onCalls - offCalls) < offCalls / 100, phase + " must have comparable offered work");
            double before = (double) offNanos / offCalls;
            double after = (double) onNanos / onCalls;
            System.out.printf(Locale.ROOT, "[Server-IT] Chunk %s: %d / %d calls, %d native operations skipped, %.1f -> %.1f ns/call%n",
                phase, offCalls, onCalls, onSaved, before, after);
            assertTrue(after < before, phase + " must take less wall time per offered call, " + before + " -> " + after);
        }
    }

    private record VehicleWindow(boolean enabled, int offered, int callbacks, long vehicleNanos, long setupNanos,
                                 long schedulerNanos) {}

    private static void verifyVehicleCadence(ServerTestContext server, StreamingWorkload workload) throws Exception {
        Path directory = server.directory();
        server.assertHook("zombie.MovingObjectUpdateScheduler", "getUpdateSchedulerSimulationLevelForObject",
            ParkedVehicleModule.class.getName(), "selectLevel");
        assertTrue(server.telemetrySnapshots().stream().anyMatch(json -> json.contains("\"vehicles.scheduler.sixteenthChecks\"")
            && json.contains("\"vehicles.scheduler.fullChecks\"")), "Expected both cadence counters in the server's telemetry");
        assertTrue(Files.isRegularFile(directory.resolve("vehicle-cadence-wakeup")), "Expected a wake-up guard check");
        var lines = Files.readAllLines(directory.resolve("vehicle-cadence.csv"));
        assertEquals(1 + 4 * workload.cycles(), lines.size(), "Four alternating windows per cycle");
        var vehicleRatios = new ArrayList<Double>();
        var schedulerRatios = new ArrayList<Double>();
        for (int cycle = 0; cycle < workload.cycles(); cycle++) {
            var windows = new ArrayList<VehicleWindow>();
            for (int block = 0; block < 4; block++) {
                String line = lines.get(1 + 4 * cycle + block);
                String[] f = line.split(",");
                boolean enabled = Boolean.parseBoolean(f[2]);
                int offered = Integer.parseInt(f[4]);
                int parked = Integer.parseInt(f[5]);
                int callbacks = Integer.parseInt(f[8]);
                int parkedCallbacks = Integer.parseInt(f[9]);
                assertEquals(cycle, Integer.parseInt(f[0]), line);
                assertEquals(block, Integer.parseInt(f[1]), line);
                assertEquals((block == 1 || block == 2) ^ (cycle % 2 == 1), enabled, line);
                assertEquals(16, Integer.parseInt(f[3]), line);
                assertTrue(offered >= 16 * 15, line);
                assertEquals(Integer.parseInt(f[6]), callbacks, "Original bucket callback count " + line);
                assertEquals(Integer.parseInt(f[7]), parkedCallbacks, "Original staggered callback count " + line);
                if (enabled) {
                    assertTrue(parked > 0 && parkedCallbacks > 0 && callbacks < offered, line);
                } else {
                    assertEquals(offered, callbacks, line);
                    assertEquals(0, parkedCallbacks, line);
                }
                windows.add(new VehicleWindow(enabled, offered, callbacks, Long.parseLong(f[10]),
                    Long.parseLong(f[11]), Long.parseLong(f[12]) + Long.parseLong(f[13])));
            }
            var off = windows.stream().filter(w -> !w.enabled()).toList();
            var on = windows.stream().filter(VehicleWindow::enabled).toList();
            int offOffered = off.stream().mapToInt(VehicleWindow::offered).sum();
            int onOffered = on.stream().mapToInt(VehicleWindow::offered).sum();
            assertEquals(offOffered, onOffered, "Same vehicle-frame workload in cycle " + cycle);
            int offCalls = off.stream().mapToInt(VehicleWindow::callbacks).sum();
            int onCalls = on.stream().mapToInt(VehicleWindow::callbacks).sum();
            assertTrue(onCalls < offCalls, "Expected less scheduled work");
            double offVehicle = totalMs(off, VehicleWindow::vehicleNanos);
            double onVehicle = totalMs(on, VehicleWindow::vehicleNanos);
            double offSetup = totalMs(off, VehicleWindow::setupNanos);
            double onSetup = totalMs(on, VehicleWindow::setupNanos);
            double offScheduler = totalMs(off, VehicleWindow::schedulerNanos);
            double onScheduler = totalMs(on, VehicleWindow::schedulerNanos);
            if (cycle > 0) {
                vehicleRatios.add(onVehicle / offVehicle);
                schedulerRatios.add((onSetup + onScheduler) / (offSetup + offScheduler));
            }
            System.out.printf(Locale.ROOT, "[Server-IT] Vehicle cadence cycle=%d offered/arm=%d calls=%d -> %d, "
                    + "vehicle body=%.3f -> %.3f ms, scheduler setup=%.3f -> %.3f ms, update+postupdate=%.3f -> %.3f ms%n",
                cycle, offOffered, offCalls, onCalls, offVehicle, onVehicle, offSetup, onSetup, offScheduler, onScheduler);
        }
        assertFaster("vehicle callback", vehicleRatios);
        assertFaster("full scheduler including setup", schedulerRatios);
    }

    private static void assertFaster(String phase, List<Double> ratios) {
        double medianRatio = ratios.stream().mapToDouble(Double::doubleValue).sorted().toArray()[ratios.size() / 2];
        assertTrue(medianRatio < 1.0, "Median matched warm " + phase + " wall-time ratio (on/off): " + medianRatio);
    }

    private static double totalMs(List<VehicleWindow> windows, ToLongFunction<VehicleWindow> metric) {
        return windows.stream().mapToLong(metric).sum() / 1_000_000.0;
    }

    private record Sample(int cycle, int chunks, long total, long max) {}

    private static Map<String, List<Sample>> readSamples(Path directory, StreamingWorkload workload) throws Exception {
        assertTrue(Files.isRegularFile(directory.resolve("streaming-complete")), "Completed streaming run");
        List<String> lines = Files.readAllLines(directory.resolve("streaming.csv"));
        assertEquals("cycle,warmup,cells,chunks,phase,calls,totalNanos,maxNanos", lines.getFirst());
        Map<String, List<Sample>> samples = new TreeMap<>();
        Map<String, Sample> cold = new TreeMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",");
            boolean coldCycle = Boolean.parseBoolean(fields[1]);
            int cycle = Integer.parseInt(fields[0]);
            assertEquals(workload.cellsFor(cycle).size(), Integer.parseInt(fields[2]));
            assertTrue(Integer.parseInt(fields[3]) > 0);
            long calls = Long.parseLong(fields[5]);
            long total = Long.parseLong(fields[6]);
            long max = Long.parseLong(fields[7]);
            assertTrue(calls >= 0 && total >= max && max >= 0, line);
            if (calls == 0) {
                assertTrue(fields[4].equals("chunk.writeWorker") || fields[4].startsWith("vehicle.")
                    || fields[4].startsWith("chunk.lifecycle."), line);
                assertEquals(0, total, line);
            } else if (!fields[4].equals("chunk.io.openCpu")) {
                assertTrue(max > 0, line);
            }
            var sample = new Sample(Integer.parseInt(fields[0]), Integer.parseInt(fields[3]), total, max);
            if (coldCycle) {
                cold.put(fields[4], sample);
                if (List.of("chunk.generate", "chunk.readSave", "chunk.loadWorker", "wall.requestToLoaded")
                        .contains(fields[4])) {
                    System.out.printf(Locale.ROOT, "[Server-IT] First load %-24s %.3f ms total, %s calls%n",
                        fields[4], total / 1_000_000.0, fields[5]);
                }
            } else {
                samples.computeIfAbsent(fields[4], key -> new ArrayList<>()).add(sample);
            }
        }
        assertTrue(cold.containsKey("chunk.generate"), "First load must exercise native generation");
        assertFalse(samples.containsKey("chunk.generate"), "Reloads must use saved chunks");
        assertTrue(samples.keySet().containsAll(List.of("chunk.readSave", "chunk.loadWorker",
            "map.preupdate", "map.postupdate", "map.relevance",
            "cell.loadQueueToRecalcQueue", "cell.recalcQueueToIntegration",
            "cell.integrate", "cell.unload", "chunk.detach", "chunk.saveWorker",
            "wall.requestToLoaded", "wall.releaseToDetached", "wall.releaseToSaveDrained")));
        for (var entries : samples.values()) {
            assertEquals(java.util.stream.IntStream.range(1, workload.cycles()).boxed().toList(),
                entries.stream().map(Sample::cycle).toList());
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
