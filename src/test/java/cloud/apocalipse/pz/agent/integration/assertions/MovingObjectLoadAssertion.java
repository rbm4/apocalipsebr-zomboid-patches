package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.integration.ServerAssertion;
import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import cloud.apocalipse.pz.agent.integration.probe.MovingObjectLoad;
import cloud.apocalipse.pz.agent.integration.probe.EntityAllocations;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class MovingObjectLoadAssertion implements ServerAssertion {
    @Override public String name() { return "Remote players and populations exercise the native moving-object scheduler"; }

    @Override public void verify(ServerTestContext server) throws Exception {
        assumeTrue(Boolean.getBoolean(MovingObjectLoad.PROPERTY), "Enable with -D" + MovingObjectLoad.PROPERTY + "=true");
        var directory = server.directory();
        var complete = directory.resolve("streaming-complete");
        var error = directory.resolve("streaming-error.txt");
        Files.createFile(directory.resolve("streaming-request"));
        server.await("moving-object workload and save drain", Duration.ofMinutes(8),
            () -> Files.isRegularFile(complete) || Files.isRegularFile(error));
        if (Files.isRegularFile(error)) fail(Files.readString(error));

        var settings = MovingObjectLoad.Settings.from(System.getProperties());
        var lines = Files.readAllLines(directory.resolve("moving-object-load.csv"));
        List<String> columns = List.of(lines.getFirst().split(","));
        List<String[]> samples = lines.stream().skip(1).map(row -> row.split(",")).toList();
        assertEquals(2 * settings.frames(), samples.size(), "One sample per measured server frame");
        var result = new StringBuilder("scenario,phase,frames,nonzeroFrames,totalMs,p50Ms,p95Ms,maxMs\n");
        for (String scenario : List.of("idle", "moving")) {
            var frames = samples.stream().filter(row -> scenario.equals(row[0])).toList();
            assertEquals(settings.frames(), frames.size(), scenario);
            assertTrue(frames.stream().allMatch(row -> integer(row, columns, "players") == settings.players()));
            if (scenario.equals("moving")) {
                assertTrue(frames.stream().anyMatch(row -> integer(row, columns, "movedPlayers") == settings.players()),
                    "Every fixture player must move on a measured native server frame");
            } else {
                assertTrue(frames.stream().allMatch(row -> integer(row, columns, "movedPlayers") == 0));
            }
            if (settings.vehicle() && scenario.equals("moving")) {
                assertTrue(frames.stream().allMatch(row -> integer(row, columns, "vehiclePacket_ns") > 0),
                    "Original packet dispatcher must accept an update each moving frame");
                assertTrue(frames.stream().anyMatch(row -> integer(row, columns, "movedVehicle") == 1),
                    "The vehicle must retain its packet-driven position after native simulation");
                verifyPhase(frames, columns, "drivenVehicleUpdate_ns");
            }
            for (String stage : List.of("PREUPDATE", "FRAME_STEP", "UPDATE", "POSTUPDATE")) {
                verifyPhase(frames, columns, "MOVING_" + stage + "_PLAYER_ns");
                if (settings.animals() > 0) verifyPhase(frames, columns, "MOVING_" + stage + "_ANIMAL_ns");
            }
            if (settings.animals() > 0) {
                assertTrue(frames.stream().anyMatch(row -> integer(row, columns, "forcedAnimalsActive") > 0));
            }
            if (settings.zombies() > 0) {
                assertTrue(frames.stream().anyMatch(row -> integer(row, columns, "forcedZombiesActive") > 0));
            }

            for (String[] row : frames) {
                long start = integer(row, columns, "schedulerStartEpochMs");
                long end = integer(row, columns, "schedulerEndEpochMs");
                assertTrue(start > 0 && start <= end && end <= integer(row, columns, "epochMs"),
                    "Real scheduler timestamps must bracket the update before the sample");
                assertTrue(integer(row, columns, "schedulerAllocatedBytes") >= -1);
                assertTrue(integer(row, columns, "mapPostupdateAllocatedBytes") >= -1);
            }
            if (Boolean.getBoolean(EntityAllocations.PROPERTY)) {
                verifyPhase(frames, columns, "PLAYER_UPDATE_calls");
                verifyPhase(frames, columns, "PLAYER_REMOTE_calls");
                verifyPhase(frames, columns, "PLAYER_BASE_calls");
                if (settings.animals() > 0) {
                    for (String scope : List.of("UPDATE", "BEHAVIOR", "DATA", "GROWTH", "LOS", "BASE")) {
                        verifyPhase(frames, columns, "ANIMAL_" + scope + "_calls");
                    }
                }
            }
            int scheduler = columns.indexOf("MOVING_ns");
            assertTrue(scheduler >= 0);
            assertTrue(frames.stream().allMatch(row -> Long.parseLong(row[scheduler]) > 0),
                "Real production scheduler telemetry must be present on every measured frame");
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).endsWith("_ns")) summarize(result, scenario, columns.get(i), frames, i);
            }
            var worst = frames.stream().max(Comparator.comparingLong(row -> Long.parseLong(row[scheduler]))).orElseThrow();
            System.out.printf(Locale.ROOT, "[Server-IT] %s worst scheduler frame %s: %.3f ms wall%n",
                scenario, worst[1], Long.parseLong(worst[scheduler]) / 1_000_000.0);
        }
        Files.writeString(directory.resolve("moving-object-load-summary.csv"), result);
    }

    private static long integer(String[] row, List<String> columns, String column) {
        return Long.parseLong(row[columns.indexOf(column)]);
    }

    private static void verifyPhase(List<String[]> frames, List<String> columns, String phase) {
        assertTrue(frames.stream().anyMatch(row -> integer(row, columns, phase) > 0), phase);
    }

    private static void summarize(StringBuilder result, String scenario, String phase, List<String[]> frames, int column) {
        long[] nanos = frames.stream().mapToLong(row -> Long.parseLong(row[column])).sorted().toArray();
        long nonzero = Arrays.stream(nanos).filter(value -> value > 0).count();
        result.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f%n", scenario, phase,
            nanos.length, nonzero, Arrays.stream(nanos).sum() / 1_000_000.0,
            nanos[(nanos.length - 1) / 2] / 1_000_000.0,
            nanos[(int) Math.ceil(0.95 * nanos.length) - 1] / 1_000_000.0,
            nanos[nanos.length - 1] / 1_000_000.0));
    }
}

