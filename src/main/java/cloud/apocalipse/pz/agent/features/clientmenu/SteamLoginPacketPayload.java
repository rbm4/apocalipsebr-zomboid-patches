package cloud.apocalipse.pz.agent.features.clientmenu;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import zombie.core.network.ByteBufferWriter;

/** Builds the vanilla LoginPacket payload for an ApocBR join authorization. */
public final class SteamLoginPacketPayload {
    /** Proposed wire value, encoded as the four bytes APBR. */
    public static final int AUTH_TYPE = 0x41504252;

    private SteamLoginPacketPayload() { }

    /** Serializes a payload in memory without touching the connection or game client state. */
    public static byte[] serialize(String username, String joinToken, String clientVersion) {
        int size = fieldSize(username, "username") + fieldSize(joinToken, "joinToken")
            + fieldSize(clientVersion, "clientVersion") + Integer.BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        ByteBufferWriter writer = new ByteBufferWriter(buffer);
        writer.putUTF(username);
        writer.putUTF(joinToken);
        writer.putUTF(clientVersion);
        writer.putInt(AUTH_TYPE);
        return buffer.array();
    }

    private static int fieldSize(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid login field: " + name);
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length > Short.MAX_VALUE) throw new IllegalArgumentException("Login field too long: " + name);
        return Short.BYTES + length;
    }
}
