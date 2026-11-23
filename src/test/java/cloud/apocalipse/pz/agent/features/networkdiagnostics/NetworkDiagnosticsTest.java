package cloud.apocalipse.pz.agent.features.networkdiagnostics;

import org.junit.jupiter.api.Test;

import static cloud.apocalipse.pz.agent.features.networkdiagnostics.NetworkDiagnostics.*;
import static cloud.apocalipse.pz.agent.features.networkdiagnostics.NetworkDiagnostics.Event.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkDiagnosticsTest {
    @Test
    void aggregatesByConnectionPacketAndEventWithBoundedHistory() {
        Key equip = new Key(0, false, "Equip", INVALID_SLOT);
        long start = -40;
        assertEquals(new Emission(1, 1), record(equip, start));
        assertNull(record(equip, start + 1));
        assertNull(record(equip, start + INTERVAL_NANOS - 1));
        assertEquals(new Emission(1, 1), record(new Key(0, true, "Equip", INVALID_SLOT), start));
        assertEquals(new Emission(1, 1), record(new Key(1, false, "Equip", INVALID_SLOT), start));
        assertEquals(new Emission(1, 1), record(new Key(0, false, "Ping", INVALID_SLOT), start));
        assertEquals(new Emission(1, 1), record(new Key(0, false, "Equip", INCONSISTENT_PACKET), start));
        assertEquals(new Emission(4, 3), record(equip, start + INTERVAL_NANOS));
        assertNull(record(equip, start + INTERVAL_NANOS + 1));

        for (int guid = 2; guid < MAX_BUCKETS + 1; guid++) {
            record(new Key(guid, false, "Equip", INVALID_SLOT), start);
        }
        assertNull(record(equip, start + INTERVAL_NANOS + 2), "Recently accessed bucket was evicted first");
        record(new Key(MAX_BUCKETS + 1, false, "Equip", INVALID_SLOT), start);
        record(new Key(MAX_BUCKETS + 2, false, "Equip", INVALID_SLOT), start);
        assertEquals(new Emission(1, 1), record(new Key(0, true, "Equip", INVALID_SLOT), start + 1));
    }
}
