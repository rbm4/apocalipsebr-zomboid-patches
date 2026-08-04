package zombie;

import zombie.debug.DebugLog;

/**
 * Minimal server-side telemetry for ApocBR patches on build 42.20.0.
 *
 * Deliberately narrow in scope: players online, zombie count, server tick rate,
 * and packet queue depth. Do not add fields for subsystems that have not been
 * ported/patched yet (deferred unload, LOS throttling, zombie network tiering,
 * moving-object buckets, vehicle Lua) - an always-zero telemetry field is worse
 * than no field, since it looks like a healthy signal.
 *
 * State (players/zombies/queues) is populated by {@link ApocBRTelemetrySampler}
 * via reflection against unmodified vanilla classes, on its own background
 * thread. World tick timing is recorded directly from the patched
 * {@code zombie.network.GameServer} main loop, since there is no vanilla field
 * that can be sampled externally to reconstruct real tick duration.
 */
public final class ApocBRServerTelemetry {
    private static final boolean ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);

    private static long nextLogMs = System.currentTimeMillis() + INTERVAL_MS;

    private static long worldTicks;
    private static long worldNanos;
    private static long worldMaxNanos;

    private static int playersLast;
    private static int zombiesLast;
    private static int connectionsLast;
    private static int highQueueLast;
    private static int playerQueueLast;
    private static int normalQueueLast;

    private ApocBRServerTelemetry() {
    }

    public static synchronized void recordWorldTick(long nanos) {
        if (!ENABLED) return;
        worldTicks++;
        worldNanos += nanos;
        worldMaxNanos = Math.max(worldMaxNanos, nanos);
    }

    public static synchronized void recordStateSnapshot(
        int players, int zombies, int connections, int highQueue, int playerQueue, int normalQueue
    ) {
        if (!ENABLED) return;
        playersLast = players;
        zombiesLast = zombies;
        connectionsLast = connections;
        highQueueLast = highQueue;
        playerQueueLast = playerQueue;
        normalQueueLast = normalQueue;
    }

    public static synchronized void maybeLog() {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now < nextLogMs) return;
        DebugLog.log(buildJsonLog(now));
        resetWorldCounters();
        nextLogMs = now + INTERVAL_MS;
    }

    private static String buildJsonLog(long now) {
        StringBuilder json = new StringBuilder(220);
        json.append("[ApocBRTelemetry]{\"ts\":").append(now);
        json.append(",\"world\":{\"ticks\":").append(worldTicks)
            .append(",\"avgMs\":").append(avgMs(worldNanos, worldTicks))
            .append(",\"maxMs\":").append(ms(worldMaxNanos))
            .append("}");
        json.append(",\"state\":{\"players\":").append(playersLast)
            .append(",\"zombies\":").append(zombiesLast)
            .append(",\"connections\":").append(connectionsLast)
            .append("}");
        json.append(",\"queues\":{\"high\":").append(highQueueLast)
            .append(",\"player\":").append(playerQueueLast)
            .append(",\"normal\":").append(normalQueueLast)
            .append("}");
        json.append("}");
        return json.toString();
    }

    private static void resetWorldCounters() {
        worldTicks = 0L;
        worldNanos = 0L;
        worldMaxNanos = 0L;
        // state/queue "last" values are intentionally left as-is: they get
        // overwritten by the next sampler snapshot regardless, and showing the
        // last known value between snapshots is more useful than resetting to 0.
    }

    private static double avgMs(long nanos, long count) {
        return count <= 0L ? 0.0 : round2((double) nanos / (double) count / 1000000.0);
    }

    private static double ms(long nanos) {
        return round2((double) nanos / 1000000.0);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static boolean getBoolean(String key, boolean def) {
        String value = System.getProperty(key);
        return value == null ? def : Boolean.parseBoolean(value.trim());
    }

    private static long getLong(String key, long def) {
        String value = System.getProperty(key);
        if (value == null) return def;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
