package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetrySnapshotPayloadTest {
    @Test
    void encodesBasicServerMeasurementsInManagerSchemaThreeSnapshot() {
        var event = new TelemetryRegistry.EventSnapshot(4, 9, 6_000_000L, 3_000_000L);
        JsonObject payload = JsonParser.parseString(TelemetrySnapshotPayload.encode(
            "42.22",
            30_003L,
            200L,
            1_795_013_352_928L,
            4,
            0L,
            new TelemetryRegistry.TickSnapshot(292, 12_114_788_000L, 408_077_000L, 15),
            new TelemetryRegistry.LoopSnapshot(4_404, 29_993_048_000L),
            564,
            Map.of("OnTick", event),
            new OptimizationTelemetry.Snapshot(
                Map.of("zombies.culling.zeroQuotaConnections", 3L),
                List.of(new OptimizationTelemetry.PhaseSample(
                    List.of("simulation", "players", "thermalClothing"), 2L, 3_000_000L, 2_000_000L)))
        )).getAsJsonObject();

        assertEquals(3, payload.get("schemaVersion").getAsInt());
        assertEquals("42.22", payload.get("gameVersion").getAsString());
        assertEquals(30_003L, payload.get("intervalMs").getAsLong());
        assertEquals(200L, payload.get("seq").getAsLong());
        assertEquals(1_795_013_352_928L, payload.get("ts").getAsLong());
        assertEquals(4, payload.get("playersOnline").getAsInt());
        assertEquals(292, payload.getAsJsonObject("tick").get("count").getAsInt());
        assertTrue(payload.getAsJsonObject("tick").has("rate"));
        assertTrue(payload.getAsJsonObject("tick").has("avgMs"));
        assertTrue(payload.getAsJsonObject("tick").has("maxMs"));
        assertEquals(9.732, payload.getAsJsonObject("tick").get("rate").getAsDouble());
        assertEquals(41.489, payload.getAsJsonObject("tick").get("avgMs").getAsDouble());
        assertEquals(408.077, payload.getAsJsonObject("tick").get("maxMs").getAsDouble());
        assertEquals(15, payload.get("overBudgetTicks").getAsInt());
        assertEquals(4_404, payload.getAsJsonObject("loop").get("count").getAsInt());
        assertEquals(29_993.048, payload.getAsJsonObject("loop").get("totalMs").getAsDouble());
        var clothingPhase = payload.getAsJsonObject("phases").getAsJsonObject("simulation")
            .getAsJsonObject("players").getAsJsonObject("thermalClothing");
        assertEquals(2L, clothingPhase.get("calls").getAsLong());
        assertEquals(3.0, clothingPhase.get("totalMs").getAsDouble());
        assertEquals(1.5, clothingPhase.get("avgMs").getAsDouble());
        assertEquals(2.0, clothingPhase.get("maxMs").getAsDouble());
        assertEquals(3L, payload.getAsJsonObject("counters").get("zombies.culling.zeroQuotaConnections").getAsLong());
        assertTrue(payload.get("world").isJsonObject());
        assertEquals(564, payload.getAsJsonObject("world").get("zombies").getAsInt());
        assertTrue(payload.get("jvm").isJsonObject());
        assertTrue(payload.getAsJsonObject("lua").get("enabled").getAsBoolean());
        assertTrue(payload.getAsJsonObject("lua").get("events").isJsonArray());
        JsonObject capturedEvent = payload.getAsJsonObject("lua").getAsJsonArray("events")
            .get(0).getAsJsonObject();
        assertEquals("OnTick", capturedEvent.get("name").getAsString());
        assertEquals(4, capturedEvent.get("calls").getAsInt());
        assertEquals(9, capturedEvent.get("callbacks").getAsInt());
        assertEquals(1.5, capturedEvent.get("avgMs").getAsDouble());
    }
}
