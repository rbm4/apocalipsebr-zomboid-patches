package cloud.apocalipse.pz.agent.features.clientmenu;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import zombie.core.network.ByteBufferReader;
import zombie.network.packets.connection.LoginPacket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SteamLoginPacketPayloadTest {
    @Test void originalLoginPacketParsesThePayload() throws ReflectiveOperationException {
        String username = "João";
        String token = "00000000-0000-4000-8000-000000000001";
        String version = "fixture-build";
        byte[] payload = SteamLoginPacketPayload.serialize(username, token, version);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        LoginPacket packet = new LoginPacket();
        packet.parse(new ByteBufferReader(buffer), null);

        assertEquals(username, field(packet, "username"));
        assertEquals(token, field(packet, "password"));
        assertEquals(version, field(packet, "clientVersion"));
        assertEquals(0x41504252, field(packet, "authType"));
        assertEquals(0, buffer.remaining());
        assertArrayEquals("APBR".getBytes(StandardCharsets.US_ASCII),
            java.util.Arrays.copyOfRange(payload, payload.length - Integer.BYTES, payload.length));
    }

    @Test void rejectsFieldsVanillaWouldTrimOrCannotRepresent() {
        assertThrows(IllegalArgumentException.class,
            () -> SteamLoginPacketPayload.serialize(" user", "fixture", "build"));
        assertThrows(IllegalArgumentException.class,
            () -> SteamLoginPacketPayload.serialize("user", "é".repeat(16_384), "build"));
    }

    private static Object field(LoginPacket packet, String name) throws ReflectiveOperationException {
        var field = LoginPacket.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(packet);
    }
}
