package zombie;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Background sampler for {@link ApocBRServerTelemetry}'s "state" metrics
 * (players, zombies, connections, packet queue depth).
 *
 * This class deliberately does NOT require overriding {@code GameServer.class}:
 * {@code GameServer.Players}, {@code GameServer.udpEngine}, and
 * {@code IsoWorld.instance.currentCell.getZombieList()} are all public and read
 * directly. The three {@code MainLoopNetData*Q} packet queues are private
 * static fields on {@code GameServer}, so they are read via reflection
 * (cached {@link Field} handles, {@code setAccessible(true)}) instead of
 * requiring a source-level override of that class just to expose them.
 *
 * Runs on its own daemon thread with its own sampling interval, decoupled from
 * the server's per-tick timing hook in {@code GameServer}'s main loop. If the
 * reflected fields are ever renamed/removed by a game update, sampling of the
 * queue-depth metrics is disabled (logged once) rather than throwing on every
 * sample; players/zombies/connections keep working regardless since those do
 * not depend on reflection at all.
 */
public final class ApocBRTelemetrySampler {
    private static final long SAMPLE_INTERVAL_MS = clamp(getLong("apocbr.telemetry.sampleIntervalMs", 5000L), 1000L, 60000L);
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private static volatile Field highQueueField;
    private static volatile Field playerQueueField;
    private static volatile Field normalQueueField;
    private static volatile boolean queueReflectionFailed;

    private ApocBRTelemetrySampler() {
    }

    public static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(ApocBRTelemetrySampler::run, "ApocBR-Telemetry-Sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        while (true) {
            try {
                sampleOnce();
            } catch (Throwable t) {
                DebugType.General.printException(t, "ApocBRTelemetrySampler: sample failed", LogSeverity.Warning);
            }

            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void sampleOnce() {
        int players = GameServer.Players.size();
        int connections = GameServer.udpEngine != null ? GameServer.udpEngine.connections.size() : 0;
        int zombies = IsoWorld.instance != null && IsoWorld.instance.currentCell != null
            ? IsoWorld.instance.currentCell.getZombieList().size()
            : 0;

        int highQueue = -1;
        int playerQueue = -1;
        int normalQueue = -1;
        if (ensureQueueFields()) {
            highQueue = queueSize(highQueueField);
            playerQueue = queueSize(playerQueueField);
            normalQueue = queueSize(normalQueueField);
        }

        ApocBRServerTelemetry.recordStateSnapshot(players, zombies, connections, highQueue, playerQueue, normalQueue);
    }

    private static boolean ensureQueueFields() {
        if (queueReflectionFailed) {
            return false;
        }

        if (highQueueField != null) {
            return true;
        }

        synchronized (ApocBRTelemetrySampler.class) {
            if (highQueueField != null) {
                return true;
            }

            try {
                Class<?> gameServerClass = GameServer.class;
                Field high = gameServerClass.getDeclaredField("MainLoopNetDataHighPriorityQ");
                Field player = gameServerClass.getDeclaredField("MainLoopPlayerUpdateQ");
                Field normal = gameServerClass.getDeclaredField("MainLoopNetDataQ");
                high.setAccessible(true);
                player.setAccessible(true);
                normal.setAccessible(true);
                highQueueField = high;
                playerQueueField = player;
                normalQueueField = normal;
                return true;
            } catch (ReflectiveOperationException | SecurityException e) {
                queueReflectionFailed = true;
                DebugType.General.warn(
                    "ApocBRTelemetrySampler: packet queue fields not found via reflection (" + e
                        + "). Queue-depth telemetry disabled; players/zombies/connections are unaffected."
                );
                return false;
            }
        }
    }

    private static int queueSize(Field field) {
        try {
            Object value = field.get(null);
            return value instanceof Collection ? ((Collection<?>) value).size() : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
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
