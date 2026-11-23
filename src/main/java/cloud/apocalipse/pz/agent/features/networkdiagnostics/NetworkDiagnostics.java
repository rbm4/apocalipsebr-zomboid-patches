package cloud.apocalipse.pz.agent.features.networkdiagnostics;

import java.util.LinkedHashMap;
import java.util.function.Supplier;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;

/** Aggregates the two receive diagnostics ported from the 42.22.2 patches. */
public final class NetworkDiagnostics {
    static final long INTERVAL_NANOS = 60_000_000_000L;
    static final int MAX_BUCKETS = 4096;
    private static final ThreadLocal<PacketType> RECEIVING = new ThreadLocal<>();
    private static final LinkedHashMap<Key, Counts> COUNTS = new LinkedHashMap<>(16, 0.75F, true);

    enum Event {
        INVALID_SLOT("invalid-player-slot"),
        INCONSISTENT_PACKET("inconsistent-packet");

        final String label;

        Event(String label) { this.label = label; }
    }

    record Key(long guid, boolean missingConnection, String packet, Event event) { }
    record Emission(long total, long sinceLast) { }

    private static final class Counts {
        long total;
        long pending;
        long lastEmission;
    }

    private NetworkDiagnostics() { }

    public static void receive(PacketType packet, ByteBufferReader buffer, UdpConnection connection) throws Exception {
        PacketType previous = RECEIVING.get();
        RECEIVING.set(packet);
        try {
            packet.onServerPacket(buffer, connection);
        } finally {
            if (previous == null) RECEIVING.remove();
            else RECEIVING.set(previous);
        }
    }

    public static boolean invalidSlot(String format, Object[] args, IConnection connection) {
        if (!GameServer.server) return false;
        try {
            report(connection, RECEIVING.get(), Event.INVALID_SLOT, () -> format.formatted(args));
            return true;
        } catch (RuntimeException ignored) {
            // Keep the original diagnostic if the additional context or formatting fails.
            return false;
        }
    }

    public static boolean inconsistentPacket(Object packet, Object connection, Object type) {
        if (!GameServer.server) return false;
        report((IConnection) connection, (PacketType) type, Event.INCONSISTENT_PACKET,
            ((INetworkPacket) packet)::getDescription);
        return true;
    }

    private static void report(IConnection connection, PacketType packet, Event event, Supplier<String> detail) {
        String packetName = packet == null ? null : packet.name();
        Key key = new Key(connection == null ? 0L : connection.getConnectedGUID(), connection == null, packetName, event);
        Emission emission = record(key, System.nanoTime());
        if (emission == null) return;
        String message = "[ApocBR][NetworkDiagnostics] connection=" + (connection == null ? "null" : connection.getIDStr())
            + " packet=" + (packetName == null ? "unknown" : packetName) + " event=" + event.label
            + " total=" + emission.total() + " sinceLast=" + emission.sinceLast()
            + " suppressed=" + (emission.sinceLast() - 1) + " detail=" + detail.get();
        if (event == Event.INVALID_SLOT) DebugType.Multiplayer.error("%s", message);
        else DebugType.Multiplayer.warn("%s", message);
    }

    // The explicit monotonic timestamp also allows deterministic cadence tests.
    static synchronized Emission record(Key key, long now) {
        Counts counts = COUNTS.get(key);
        boolean first = counts == null;
        if (first) {
            if (COUNTS.size() == MAX_BUCKETS) {
                COUNTS.pollFirstEntry();
            }
            counts = new Counts();
            COUNTS.put(key, counts);
        }
        counts.total++;
        counts.pending++;
        if (!first && now - counts.lastEmission < INTERVAL_NANOS) return null;

        Emission emission = new Emission(counts.total, counts.pending);
        counts.pending = 0;
        counts.lastEmission = now;
        return emission;
    }
}
