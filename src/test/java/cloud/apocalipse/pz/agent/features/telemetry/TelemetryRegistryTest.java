package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryRegistryTest {
    @Test
    void luaEventSnapshotsAggregateCallbacksAndResetPerInterval() {
        String name = "test." + UUID.randomUUID();
        TelemetryRegistry.recordLuaEvent(name, 2, 1_000_000L);
        TelemetryRegistry.recordLuaEvent(name, 3, 2_000_000L);

        TelemetryRegistry.EventSnapshot snapshot = TelemetryRegistry.snapshotAndReset().get(name);
        assertNotNull(snapshot);
        assertEquals(2L, snapshot.calls());
        assertEquals(5L, snapshot.callbacks());
        assertEquals(3_000_000L, snapshot.totalNanos());
        assertEquals(2_000_000L, snapshot.maxNanos());
        assertFalse(TelemetryRegistry.snapshotAndReset().containsKey(name));
    }

    @Test
    void luaEventNamesHaveABoundedOverflowBucket() {
        String prefix = "high-cardinality." + UUID.randomUUID() + ".";
        for (int i = 0; i < 256; i++) {
            TelemetryRegistry.recordLuaEvent(prefix + i, 1, 1L);
        }

        var snapshot = TelemetryRegistry.snapshotAndReset();
        assertTrue(snapshot.size() <= 128);
        assertTrue(snapshot.get("(other)").calls() > 0);
    }
}
