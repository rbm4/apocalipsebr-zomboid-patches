package cloud.apocalipse.pz.agent.features.steampeer;

import java.nio.ByteBuffer;

final class SteamPeerExchange {
    static final int MESSAGE_SIZE = Integer.BYTES + Byte.BYTES + Long.BYTES;
    private static final int MAGIC = 0x41505031; // APP1
    private static final byte PING = 1;
    private static final byte PONG = 2;
    private final long peer;
    private final long nonce;
    private boolean confirmed;

    SteamPeerExchange(long peer, long nonce) {
        this.peer = peer;
        this.nonce = nonce;
    }

    byte[] ping() {
        return encode(PING, nonce);
    }

    boolean confirmed() {
        return confirmed;
    }

    byte[] receive(long sender, byte[] payload) {
        if (sender != peer || payload.length != MESSAGE_SIZE) return null;
        ByteBuffer input = ByteBuffer.wrap(payload);
        if (input.getInt() != MAGIC) return null;
        byte kind = input.get();
        long token = input.getLong();
        if (kind == PING) return encode(PONG, token);
        if (kind == PONG && token == nonce) confirmed = true;
        return null;
    }

    private static byte[] encode(byte kind, long token) {
        return ByteBuffer.allocate(MESSAGE_SIZE).putInt(MAGIC).put(kind).putLong(token).array();
    }
}

