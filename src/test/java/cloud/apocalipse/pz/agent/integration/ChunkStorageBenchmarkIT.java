package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.experimental.chunks.PostgresChunksModule;
import cloud.apocalipse.pz.agent.integration.probe.ChunkStorageArm;
import cloud.apocalipse.pz.agent.integration.probe.StreamingWorkload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One benchmark invocation, with a fresh instance of the same streaming suite for each arm. */
class ChunkStorageBenchmarkIT {
    private static final List<String> METRICS = List.of("wall.requestToLoaded", "wall.releaseToDetached",
        "wall.releaseToSaveDrained", "chunk.loadWorker", "cell.integrate", "chunk.saveWorker", "chunk.storageWrite");

    @Test
    void comparesNativePersistencePaths() throws Exception {
        String bin = System.getProperty("pz.it.postgresBin");
        assertNotNull(bin, "Set -Dpz.it.postgresBin to the disposable PostgreSQL binaries directory");
        Properties common = commonSettings();
        var workload = StreamingWorkload.from(common);
        Path parent = Files.createDirectories(Path.of("target/chunk-storage-benchmark").toAbsolutePath());
        Path output = Files.createTempDirectory(parent, "run-");
        System.out.println("[Chunk-IT] Artifacts: " + output);
        var runs = new EnumMap<ChunkStorageArm, Run>(ChunkStorageArm.class);
        try (var postgres = DisposableChunkPostgres.start(output, Path.of(bin))) {
            Run baseline = run(output, postgres, common, workload, ChunkStorageArm.DISK_FRESH, null);
            runs.put(ChunkStorageArm.DISK_FRESH, baseline);
            for (var arm : List.of(ChunkStorageArm.POSTGRES_FRESH, ChunkStorageArm.DISK_SEEDED,
                    ChunkStorageArm.POSTGRES_SEEDED)) {
                Run result = run(output, postgres, common, workload, arm, baseline.directory());
                assertEquals(baseline.manifest().getProperty("game.sha256"), result.manifest().getProperty("game.sha256"));
                assertEquals(baseline.manifest().getProperty("agent.sha256"), result.manifest().getProperty("agent.sha256"));
                runs.put(arm, result);
            }
        }
        writeComparison(output, runs);
    }

    private static Properties commonSettings() {
        Properties settings = new Properties();
        for (String key : List.of("pz.game.jar", "pz.it.home", "pz.it.java", "pz.it.nativePath",
                "pz.it.heap", "pz.it.startupSeconds", "pz.it.shutdownSeconds", "pz.it.serverLogs", StreamingWorkload.PROFILE)) {
            String value = System.getProperty(key);
            if (value != null) settings.setProperty(key, value);
        }
        settings.putIfAbsent("pz.it.serverLogs", "false");
        settings.putIfAbsent("pz.it.heap", "6g");
        settings.putIfAbsent(StreamingWorkload.PROFILE, "players30");
        assertTrue(List.of("smoke", "players30").contains(settings.getProperty(StreamingWorkload.PROFILE)),
            "Use smoke or players30 for matched chunk storage workloads");
        settings.setProperty("pz.it.streaming", "true");
        return settings;
    }

    private static Run run(Path output, DisposableChunkPostgres postgres, Properties common, StreamingWorkload workload,
                           ChunkStorageArm arm, Path baseline) throws Exception {
        Properties settings = new Properties(common);
        settings.setProperty(ChunkStorageArm.PROPERTY, arm.name());
        settings.setProperty(ChunkMigrationFixture.PORT, Integer.toString(postgres.port()));
        String database = arm.seeded() ? DisposableChunkPostgres.SEEDED : DisposableChunkPostgres.FRESH;
        settings.setProperty(ChunkMigrationFixture.BENCHMARK_DATABASE, database);
        if (arm.seeded()) settings.setProperty(ChunkMigrationFixture.BENCHMARK_SEED, baseline.toString());
        System.out.println("[Chunk-IT] Starting " + arm);
        try (var server = ServerTestContext.start(settings)) {
            Path directory = server.directory();
            Files.writeString(output.resolve(arm.name() + ".txt"), directory + System.lineSeparator());
            Path error = directory.resolve("streaming-error.txt");
            Path map = ChunkMigrationFixture.map(directory);
            Path baselineMap = baseline == null ? map : ChunkMigrationFixture.map(baseline);
            var cache = new StringBuilder("cycle,preparation,work,wallNanos\n");
            Files.createFile(directory.resolve("streaming-request"));
            for (int cycle = 0; cycle < arm.cycles(workload); cycle++) {
                long started = System.nanoTime();
                if (arm.postgres() && cycle > 0) {
                    long blocks = postgres.prewarm(database);
                    cache.append(cycle).append(",postgres_blocks,").append(blocks).append(',')
                        .append(System.nanoTime() - started).append('\n');
                } else if (arm.seeded()) {
                    long bytes = warmFiles(map, workload.cells().size() * 64);
                    cache.append(cycle).append(",file_bytes,").append(bytes).append(',')
                        .append(System.nanoTime() - started).append('\n');
                }
                Path drained = directory.resolve("storage-drained-" + cycle);
                Files.createFile(directory.resolve("storage-start-" + cycle));
                server.await(arm + " cycle " + cycle + " save drain", Duration.ofSeconds(workload.cycleTimeoutSeconds() + 15),
                    () -> Files.isRegularFile(drained) || Files.isRegularFile(error));
                if (Files.isRegularFile(error)) fail(Files.readString(error));
            }
            server.await(arm + " measurements", Duration.ofSeconds(10),
                () -> Files.isRegularFile(directory.resolve("streaming-complete")) || Files.isRegularFile(error));
            if (Files.isRegularFile(error)) fail(Files.readString(error));
            Files.writeString(output.resolve(arm.name() + "-cache.csv"), cache);
            Files.copy(directory.resolve("streaming.csv"), output.resolve(arm.name() + "-streaming.csv"));
            if (arm.postgres()) {
                verifyPostgres(server, settings, arm, baselineMap);
            } else {
                assertEquals(workload.cells().size() * 64, ChunkMigrationFixture.chunkFiles(map).size());
            }
            Properties manifest = new Properties();
            try (var input = Files.newInputStream(directory.resolve("runtime.properties"))) {
                manifest.load(input);
            }
            return new Run(directory, manifest, measurements(directory, arm, workload));
        }
    }

    private static long warmFiles(Path map, int expected) throws Exception {
        List<Path> files = ChunkMigrationFixture.chunkFiles(map);
        assertEquals(expected, files.size(), "Disk cache preparation needs the whole workload");
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.readAllBytes(map.resolve(file)).length;
        }
        return bytes;
    }

    private static void verifyPostgres(ServerTestContext server, Properties settings, ChunkStorageArm arm, Path baseline) throws Exception {
        List<Path> expected = ChunkMigrationFixture.chunkFiles(baseline);
        var map = ChunkMigrationFixture.map(server.directory());
        assertEquals(arm.seeded() ? expected : List.of(), ChunkMigrationFixture.chunkFiles(map), "Database save must leave disk untouched");
        if (arm.seeded()) {
            for (Path file : expected) {
                assertEquals(-1, Files.mismatch(baseline.resolve(file), map.resolve(file)), "Legacy file changed: " + file);
            }
        }
        ChunkMigrationFixture.verifyRows(settings, expected);
        for (var hook : new PostgresChunksModule().hookChecks()) {
            server.assertHook(hook.targetClass(), hook.method(), hook.helperClass(), hook.helperMethod());
        }
    }

    private static Map<String, long[]> measurements(Path directory, ChunkStorageArm arm, StreamingWorkload workload) throws Exception {
        var phases = new HashMap<String, long[]>();
        int cycles = arm.cycles(workload);
        for (String line : Files.readAllLines(directory.resolve("streaming.csv")).stream().skip(1).toList()) {
            String[] fields = line.split(",");
            if (!METRICS.contains(fields[4])) continue;
            assertEquals(workload.cells().size() * 64, Integer.parseInt(fields[3]), "Matched chunk count");
            long[] totals = phases.computeIfAbsent(fields[4], ignored -> new long[cycles]);
            int cycle = Integer.parseInt(fields[0]);
            assertEquals(0, totals[cycle], "Duplicate cycle measurement");
            totals[cycle] = Long.parseLong(fields[6]);
            assertTrue(totals[cycle] > 0, line);
        }
        for (String metric : METRICS) {
            assertNotNull(phases.get(metric), metric);
            for (long total : phases.get(metric)) assertTrue(total > 0, "Missing sample for " + metric);
        }
        return phases;
    }

    private static void writeComparison(Path output, Map<ChunkStorageArm, Run> runs) throws Exception {
        var report = new StringBuilder("scenario,phase,samples,diskMs,postgresMs,postgresMinusDiskMs\n");
        compare(report, "generate-and-save", runs.get(ChunkStorageArm.DISK_FRESH),
            runs.get(ChunkStorageArm.POSTGRES_FRESH), 0, 1);
        compare(report, "disk-load-and-migrate", runs.get(ChunkStorageArm.DISK_SEEDED),
            runs.get(ChunkStorageArm.POSTGRES_SEEDED), 0, 1);
        compare(report, "warm-reload-and-save", runs.get(ChunkStorageArm.DISK_SEEDED),
            runs.get(ChunkStorageArm.POSTGRES_SEEDED), 1, runs.get(ChunkStorageArm.DISK_SEEDED).phases().get(METRICS.getFirst()).length);
        Files.writeString(output.resolve("comparison.csv"), report);
        System.out.println(report);
    }

    private static void compare(StringBuilder report, String scenario, Run disk, Run postgres, int from, int to) {
        for (String metric : METRICS) {
            double a = medianMs(disk.phases().get(metric), from, to);
            double b = medianMs(postgres.phases().get(metric), from, to);
            report.append(String.format(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%+.3f%n", scenario, metric, to - from, a, b, b - a));
        }
    }

    private static double medianMs(long[] samples, int from, int to) {
        long[] ordered = java.util.Arrays.copyOfRange(samples, from, to);
        java.util.Arrays.sort(ordered);
        return (ordered[ordered.length / 2] / 2.0 + ordered[(ordered.length - 1) / 2] / 2.0) / 1_000_000.0;
    }

    private record Run(Path directory, Properties manifest, Map<String, long[]> phases) {}
}
