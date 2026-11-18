package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetrySnapshotPayloadTest {
    @Test
    void encodesLuaMeasurementsInManagerSchemaThreeSnapshot() {
        var event = new TelemetryRegistry.EventSnapshot(4, 9, 6_000_000L, 3_000_000L);
        JsonObject payload = JsonParser.parseString(TelemetrySnapshotPayload.encode(
            "42.22.2", 30_000L, 1L, 1_795_000_000_000L, 3, 0L, Map.of("OnTick", event)
        )).getAsJsonObject();

        assertEquals(3, payload.get("schemaVersion").getAsInt());
        assertEquals("42.22.2", payload.get("gameVersion").getAsString());
        assertEquals(30_000L, payload.get("intervalMs").getAsLong());
        assertEquals(1L, payload.get("seq").getAsLong());
        assertEquals(1_795_000_000_000L, payload.get("ts").getAsLong());
        assertEquals(3, payload.get("playersOnline").getAsInt());
        assertEquals(0, payload.getAsJsonObject("tick").get("count").getAsInt());
        assertTrue(payload.getAsJsonObject("tick").has("rate"));
        assertTrue(payload.getAsJsonObject("tick").has("avgMs"));
        assertTrue(payload.getAsJsonObject("tick").has("maxMs"));
        assertTrue(payload.getAsJsonObject("loop").has("count"));
        assertTrue(payload.getAsJsonObject("loop").has("totalMs"));
        assertEquals(0, payload.get("overBudgetTicks").getAsInt());
        assertTrue(payload.get("phases").isJsonObject());
        assertTrue(payload.get("counters").isJsonObject());
        assertTrue(payload.get("world").isJsonObject());
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
