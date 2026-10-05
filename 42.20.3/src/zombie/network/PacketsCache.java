// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: see the ApocBR comments below.
package zombie.network;

import java.util.HashMap;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.packets.INetworkPacket;

/**
 * ApocBR notes on {@link #isLimitExceeded}.
 *
 * <p>This method runs once per received packet, per connection, on the main thread. The vanilla
 * implementation was a surprisingly expensive allocator for something on that path:
 *
 * <ul>
 *   <li>{@code HashMap<PacketType, List<Long>>} of {@code LinkedList<Long>}. Every recorded packet
 *       allocated a boxed {@code Long} (epoch millis are far outside the {@code Long.valueOf}
 *       cache, so every one is a fresh object) plus a {@code LinkedList.Node}.
 *   <li>It then iterated <em>every</em> packet type's list on <em>every</em> call and ran two
 *       {@code removeIf} passes over each. Both lambdas capture {@code currentTime}, so they cannot
 *       be cached as singletons: that is two more allocations per list per call.
 * </ul>
 *
 * <p>With ~50 distinct packet types seen per connection that is on the order of 100 short-lived
 * objects per packet, multiplied by the packet rate and by the player count. It is pure per-player
 * garbage generated in direct proportion to how busy the server is.
 *
 * <p>This version stores timestamps in a per-type primitive {@code long} ring buffer indexed by
 * enum ordinal: zero boxing, zero lambdas, and pruning touches only the type being queried.
 * Pruning only the queried type is observably equivalent, because the return value depends solely
 * on the size of that one type's window; stale entries parked in other types' windows never
 * influenced the decision.
 */
public abstract class PacketsCache {
    private static final int TYPE_COUNT = PacketTypes.PacketType.values().length;

    private final HashMap<PacketTypes.PacketType, INetworkPacket> packets = new HashMap<>();
    // ApocBR: was HashMap<PacketType, Integer>, which boxed an Integer on every comparison.
    private final int[] hashes = new int[TYPE_COUNT];
    private final boolean[] hashPresent = new boolean[TYPE_COUNT];
    // ApocBR: per-type ring buffer of raw timestamps. Lazily allocated so a connection only pays
    // for the packet types it actually uses.
    private final long[][] limitTimes = new long[TYPE_COUNT][];
    private final int[] limitStart = new int[TYPE_COUNT];
    private final int[] limitSize = new int[TYPE_COUNT];

    protected PacketsCache() {
        StringBuilder stringBuilder = new StringBuilder();

        for (PacketTypes.PacketType packetType : PacketTypes.PacketType.values()) {
            if (packetType.handler == null) {
                if (!stringBuilder.isEmpty()) {
                    stringBuilder.append(", ");
                }

                stringBuilder.append(packetType.name());
            } else {
                try {
                    this.packets.put(packetType, packetType.handler.getDeclaredConstructor().newInstance());
                } catch (Exception var7) {
                    DebugType.Packet.printException(var7, LogSeverity.Warning, "Error creating packet type: \"%s\"", packetType.name());
                }
            }
        }

        if (!stringBuilder.isEmpty()) {
            stringBuilder.insert(0, "No packet handler for type: ");
            DebugType.Packet.warn(stringBuilder.toString());
        }
    }

    public INetworkPacket getPacket(PacketTypes.PacketType packetType) {
        return this.packets.get(packetType);
    }

    public boolean isHashEquals(PacketTypes.PacketType packetType, Integer hash) {
        // ApocBR: same contract as the old map version. Vanilla did hash.equals(map.put(..)), which
        // is false when no previous value existed, so absence must return false here too.
        int ordinal = packetType.ordinal();
        int value = hash;
        boolean had = this.hashPresent[ordinal];
        int previous = this.hashes[ordinal];
        this.hashes[ordinal] = value;
        this.hashPresent[ordinal] = true;
        return had && previous == value;
    }

    public boolean isLimitExceeded(PacketTypes.PacketType packetType) {
        long currentTime = System.currentTimeMillis();
        int ordinal = packetType.ordinal();
        int maxPerSecond = ServerOptions.getInstance().maxPacketsPerSecond.getValue();
        long[] times = this.ensureLimitCapacity(ordinal, maxPerSecond);
        int capacity = times.length;

        // ApocBR: entries are appended in time order, so everything expired is contiguous at the
        // head. Vanilla also dropped timestamps in the future, which only happens if the wall clock
        // jumps backwards; in that case every entry is in the future and they are still contiguous
        // from the head, so a single head scan covers both cases.
        while (this.limitSize[ordinal] > 0) {
            long timestamp = times[this.limitStart[ordinal]];
            if (currentTime > timestamp + 1000L || currentTime < timestamp) {
                this.limitStart[ordinal] = (this.limitStart[ordinal] + 1) % capacity;
                this.limitSize[ordinal]--;
            } else {
                break;
            }
        }

        // ApocBR: preserve vanilla's asymmetry exactly. The client records before the check, the
        // server records only after passing it.
        if (GameClient.client) {
            this.recordLimit(ordinal, currentTime);
        }

        if (this.limitSize[ordinal] <= maxPerSecond) {
            if (GameServer.server) {
                this.recordLimit(ordinal, currentTime);
            }

            return false;
        } else {
            DebugType.Multiplayer.warn("Packets limit has exceeded for %s", packetType.name());
            return true;
        }
    }

    /**
     * ApocBR: lazily size the ring buffer for one packet type. Capacity is maxPerSecond + 2 so the
     * window can hold the maxPerSecond + 1 entries needed to detect the overflow condition. If an
     * admin raises maxPacketsPerSecond at runtime the buffer is regrown in order.
     */
    private long[] ensureLimitCapacity(int ordinal, int maxPerSecond) {
        int required = Math.max(2, maxPerSecond + 2);
        long[] times = this.limitTimes[ordinal];
        if (times != null && times.length >= required) {
            return times;
        }

        long[] grown = new long[required];
        int size = 0;
        if (times != null) {
            int capacity = times.length;
            for (int i = 0; i < this.limitSize[ordinal]; i++) {
                grown[size++] = times[(this.limitStart[ordinal] + i) % capacity];
            }
        }

        this.limitTimes[ordinal] = grown;
        this.limitStart[ordinal] = 0;
        this.limitSize[ordinal] = size;
        return grown;
    }

    private void recordLimit(int ordinal, long currentTime) {
        long[] times = this.limitTimes[ordinal];
        int capacity = times.length;
        if (this.limitSize[ordinal] == capacity) {
            // Full: overwrite the oldest rather than grow. The window is already past the limit,
            // so the decision cannot change and unbounded growth would be the real hazard.
            times[this.limitStart[ordinal]] = currentTime;
            this.limitStart[ordinal] = (this.limitStart[ordinal] + 1) % capacity;
            return;
        }

        times[(this.limitStart[ordinal] + this.limitSize[ordinal]) % capacity] = currentTime;
        this.limitSize[ordinal]++;
    }
}
