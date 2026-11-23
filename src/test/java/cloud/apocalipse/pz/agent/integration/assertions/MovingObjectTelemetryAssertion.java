package cloud.apocalipse.pz.agent.integration.assertions;

import cloud.apocalipse.pz.agent.integration.ServerTestContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class MovingObjectTelemetryAssertion {
    private MovingObjectTelemetryAssertion() {}

    static void drive(ServerTestContext server) throws Exception {
        var directory = server.directory();
        var error = directory.resolve("streaming-error.txt");
        try {
            server.await("vehicle fixture cells to integrate", Duration.ofSeconds(110), () ->
                Files.isRegularFile(directory.resolve("moving-object-ready")) || Files.isRegularFile(error));
            if (Files.isRegularFile(error)) fail(Files.readString(error));
            int delivered = server.telemetrySnapshots().size();
            server.await("fresh telemetry window after cell integration", Duration.ofSeconds(65), () ->
                server.telemetrySnapshots().size() > delivered);
            int first = server.telemetrySnapshots().size();
            Files.createFile(directory.resolve("moving-object-arm"));
            server.await("moving-object callback attribution", Duration.ofSeconds(65), () ->
                server.telemetrySnapshots().stream().skip(first).anyMatch(MovingObjectTelemetryAssertion::attributesSpike));
            var evidence = directory.resolve("moving-object-spike.txt");
            assertTrue(Files.isRegularFile(evidence), "Fixture must reach a native scheduled vehicle");
            assertEquals("native vehicle update returned", Files.readString(evidence));
        } finally {
            Files.createFile(directory.resolve("moving-object-release"));
        }
    }

    private static boolean attributesSpike(String payload) {
        JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
        JsonObject moving = path(root, "phases", "simulation", "movingObjects");
        if (moving == null) return false;
        JsonObject vehicleUpdate = path(moving, "callbacks", "update", "vehicle");
        if (vehicleUpdate == null || vehicleUpdate.get("maxMs").getAsDouble() < 700) return false;
        JsonObject peak = path(root, "tick", "slowest", "phaseMs");
        String updateKey = "simulation.movingObjects.callbacks.update.vehicle";
        assertNotNull(peak, "Expected a slowest-tick diagnostic in the spike window");
        assertTrue(peak.has(updateKey) && peak.get(updateKey).getAsDouble() >= 700,
            "The delayed vehicle must be identified in the same tick");
        assertTrue(peak.get("simulation.movingObjects.update").getAsDouble() >= peak.get(updateKey).getAsDouble());
        JsonObject scheduler = path(moving, "update");
        assertNotNull(scheduler, "Inclusive scheduler timer must also be present");
        assertTrue(scheduler.get("maxMs").getAsDouble() >= vehicleUpdate.get("maxMs").getAsDouble());
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        if (threads.isCurrentThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()) {
            JsonObject cpu = path(moving, "cpu", "update");
            assertNotNull(cpu, "Scheduler CPU timer must also be present");
            assertEquals(scheduler.get("calls").getAsLong(), cpu.get("calls").getAsLong());
        }
        for (String stage : List.of("preupdate", "frameStep", "postupdate")) {
            JsonObject sample = path(moving, "callbacks", stage, "vehicle");
            assertNotNull(sample, stage);
            assertTrue(sample.get("calls").getAsLong() > 0, stage);
        }
        return true;
    }

    private static JsonObject path(JsonObject node, String... keys) {
        for (String key : keys) {
            if (node == null || !node.has(key)) return null;
            node = node.getAsJsonObject(key);
        }
        return node;
    }
}
