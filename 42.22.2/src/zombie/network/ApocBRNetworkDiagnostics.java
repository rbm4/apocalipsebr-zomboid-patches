package zombie.network;

import java.util.LinkedHashMap;
import java.util.function.Supplier;
import zombie.debug.DebugType;

/** Server receive diagnostics only; never changes packet validation or processing. */
public final class ApocBRNetworkDiagnostics {
    static final long INTERVAL_NANOS = 60_000_000_000L;
    static final int MAX_BUCKETS = 4096;
    private static final ThreadLocal<PacketTypes.PacketType> RECEIVING = new ThreadLocal<>();
    private static final LinkedHashMap<Key, Counts> COUNTS = new LinkedHashMap<>(16, 0.75F, true);

    private record Key(long connection, boolean missingConnection, PacketTypes.PacketType packet, String event, boolean error) { }
    private static final class Counts {
        long total;
        long pending;
        long lastEmission;
    }

    private ApocBRNetworkDiagnostics() { }

    /** Nestable receive scope; close in finally/try-with-resources even on parse failures. */
    public static Scope receiving(PacketTypes.PacketType packet) {
        PacketTypes.PacketType previous = RECEIVING.get();
        RECEIVING.set(packet);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final PacketTypes.PacketType previous;
        private Scope(PacketTypes.PacketType previous) { this.previous = previous; }
        @Override
        public void close() {
            if (this.previous == null) RECEIVING.remove();
            else RECEIVING.set(this.previous);
        }
    }

    public static void error(IConnection connection, String event, Supplier<String> detail) {
        report(connection, RECEIVING.get(), event, true, detail);
    }

    public static void warn(IConnection connection, PacketTypes.PacketType packet, String event, Supplier<String> detail) {
        report(connection, packet, event, false, detail);
    }

    private static void report(IConnection connection, PacketTypes.PacketType packet, String event, boolean error, Supplier<String> detail) {
        reportAt(connection, packet, event, error, detail, System.nanoTime());
    }

    // Explicit monotonic time keeps cadence boundary tests deterministic.
    static synchronized void reportAt(IConnection connection, PacketTypes.PacketType packet, String event,
                                      boolean error, Supplier<String> detail, long now) {
        Key key = new Key(connection == null ? 0L : connection.getConnectedGUID(), connection == null, packet, event, error);
        Counts counts = COUNTS.get(key);
        boolean first = counts == null;
        if (first) {
            if (COUNTS.size() >= MAX_BUCKETS) {
                var oldest = COUNTS.entrySet().iterator();
                oldest.next();
                oldest.remove();
            }
            counts = new Counts();
            COUNTS.put(key, counts);
        }
        counts.total++;
        counts.pending++;
        if (!first && now - counts.lastEmission < INTERVAL_NANOS) return;

        String message = "[ApocBR][NetworkDiagnostics] connection=" + (connection == null ? "null" : connection.getIDStr())
            + " packet=" + (packet == null ? "unknown" : packet.name()) + " event=" + event
            + " total=" + counts.total + " sinceLast=" + counts.pending + " suppressed=" + (counts.pending - 1)
            + " detail=" + detail.get();
        counts.pending = 0;
        counts.lastEmission = now;
        if (error) DebugType.Multiplayer.error("%s", message);
        else DebugType.Multiplayer.warn("%s", message);
    }
}
