package cloud.apocalipse.pz.agent.features.lua.api;

import cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryOrigin;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CourierItemCodecTest {
    @Test void portableHeaderPreservesExactSourceAndLeavesNativeBytesUntouched() {
        var origin = new DictionaryOrigin(7, UUID.randomUUID(), UUID.randomUUID());
        var buffer = ByteBuffer.allocate(48);
        CourierItemCodec.writeHeader(buffer, 250, origin);
        buffer.putInt(4).flip();

        var metadata = CourierItemCodec.readHeader(buffer, 251);
        assertEquals(origin, metadata.origin());
        assertEquals(250, metadata.worldVersion());
        assertEquals(4, buffer.getInt());
        assertFalse(buffer.hasRemaining());
    }

    @Test void oldCustodyKeepsItsNativeFormatAndLocalOrigin() {
        var buffer = ByteBuffer.allocate(8).putInt(250).putInt(4).flip();
        var metadata = CourierItemCodec.readHeader(buffer, 251);
        assertNull(metadata.origin());
        assertEquals(250, metadata.worldVersion());
        assertEquals(4, buffer.getInt());
    }

    @Test void rejectsTruncatedOriginFutureVersionsAndOversizeLegacyBlobs() {
        var origin = new DictionaryOrigin(7, UUID.randomUUID(), UUID.randomUUID());
        var buffer = ByteBuffer.allocate(48);
        CourierItemCodec.writeHeader(buffer, 252, origin);
        buffer.putInt(4).flip();
        assertThrows(IllegalArgumentException.class, () -> CourierItemCodec.readHeader(buffer, 251));

        buffer.rewind().limit(12);
        assertThrows(BufferUnderflowException.class, () -> CourierItemCodec.readHeader(buffer, 252));
        var oversized = ByteBuffer.allocate(1_048_577).putInt(250).rewind();
        assertThrows(IllegalArgumentException.class, () -> CourierItemCodec.readHeader(oversized, 251));
    }

    @Test void pickupRecoveryComparesNativeStateAcrossHeaderVersions() {
        var origin = new DictionaryOrigin(7, UUID.randomUUID(), UUID.randomUUID());
        var portable = ByteBuffer.allocate(48);
        CourierItemCodec.writeHeader(portable, 251, origin);
        portable.putInt(4).flip();
        var legacy = ByteBuffer.allocate(8).putInt(251).putInt(4).flip();
        assertTrue(CourierItemCodec.sameNativeState(portable, legacy, 251));
        var changed = ByteBuffer.allocate(8).putInt(251).putInt(5).flip();
        assertFalse(CourierItemCodec.sameNativeState(portable.rewind(), changed, 251));
    }

    @Test void localNamespaceSurvivesNewViewsButNeverMatchesForeignOrResetWorlds() {
        var origin = new DictionaryOrigin(7, UUID.randomUUID(), UUID.randomUUID());
        assertTrue(CourierItemCodec.sameNamespace(origin, new DictionaryOrigin(7, origin.namespaceId(), UUID.randomUUID())));
        assertFalse(CourierItemCodec.sameNamespace(origin, new DictionaryOrigin(8, origin.namespaceId(), origin.viewId())));
        assertFalse(CourierItemCodec.sameNamespace(origin, new DictionaryOrigin(7, UUID.randomUUID(), origin.viewId())));
        assertFalse(CourierItemCodec.sameNamespace(origin, null));
    }
}
