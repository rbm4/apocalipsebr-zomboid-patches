package cloud.apocalipse.pz.agent.features.clientmenu;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import zombie.core.network.ByteBufferReader;
import zombie.network.packets.connection.LoginPacket;

/** Checks the draft payload using the original game classes in an isolated JVM. */
public final class SteamLoginPacketPayloadOriginalMain {
    public static void main(String[] args) throws Exception {
        Path gameJar = Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[] {LoginPacket.class, ByteBufferReader.class}) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!origin.equals(gameJar)) throw new AssertionError("Unexpected game class origin: " + origin);
        }

        String username = "João";
        String token = "00000000-0000-4000-8000-000000000001";
        String version = "fixture-build";
        byte[] payload = SteamLoginPacketPayload.serialize(username, token, version);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        LoginPacket packet = new LoginPacket();
        packet.parse(new ByteBufferReader(buffer), null);

        requireField(packet, "username", username);
        requireField(packet, "password", token);
        requireField(packet, "clientVersion", version);
        requireField(packet, "authType", 0x41504252);
        if (buffer.hasRemaining() || buffer.getInt(payload.length - Integer.BYTES) != 0x41504252) {
            throw new AssertionError("Unexpected login payload boundary");
        }
    }

    private static void requireField(LoginPacket packet, String name, Object expected) throws ReflectiveOperationException {
        var field = LoginPacket.class.getDeclaredField(name);
        field.setAccessible(true);
        if (!expected.equals(field.get(packet))) throw new AssertionError("Unexpected login field: " + name);
    }
}
