package zombie;

import zombie.debug.DebugLog;

public final class ApocBRServerTelemetry {
    private static final boolean ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);
    private static long nextLogMs = System.currentTimeMillis() + INTERVAL_MS;
    private static int highQueueLast;
    private static int playerQueueLast;
    private static int normalQueueLast;
    private static int connectionsLast;
    private static int playersLast;
    private static int zombiesLast;
    private static long highPackets;
    private static long highNanos;
    private static long highMaxNanos;
    private static long playerPackets;
    private static long playerNanos;
    private static long playerMaxNanos;
    private static long normalPackets;
    private static long normalNanos;
    private static long normalMaxNanos;
    private static long worldTicks;
    private static long worldNanos;
    private static long worldMaxNanos;
    private static long serverMapPreNanos;
    private static long serverMapPreMaxNanos;
    private static long coreWorldNanos;
    private static long coreWorldMaxNanos;
    private static long mapCollisionNanos;
    private static long mapCollisionMaxNanos;
    private static long stateUpdateNanos;
    private static long stateUpdateMaxNanos;
    private static long vehicleUpdateNanos;
    private static long vehicleUpdateMaxNanos;
    private static long objectIdNanos;
    private static long objectIdMaxNanos;
    private static long connectionChunkNanos;
    private static long connectionChunkMaxNanos;
    private static long serverMapPostNanos;
    private static long serverMapPostMaxNanos;
    private static long downloadConnections;
    private static long chunkMainCalls;
    private static long chunkMainNanos;
    private static long chunkMainMaxNanos;
    private static long chunkMainRequests;
    private static long chunkMainPrepared;
    private static int chunkMainMaxWaiting;
    private static long chunkWorkerCalls;
    private static long chunkWorkerNanos;
    private static long chunkWorkerMaxNanos;
    private static long chunkWorkerChunks;

    private ApocBRServerTelemetry() {
    }

    public static synchronized void recordQueueSnapshot(int highQueue, int playerQueue, int normalQueue, int connections, int players, int zombies) {
        if (!ENABLED) return;
        highQueueLast = highQueue;
        playerQueueLast = playerQueue;
        normalQueueLast = normalQueue;
        connectionsLast = connections;
        playersLast = players;
        zombiesLast = zombies;
    }

    public static synchronized void recordPacketDrain(String queue, int count, long nanos) {
        if (!ENABLED) return;
        if ("high".equals(queue)) {
            highPackets += count;
            highNanos += nanos;
            highMaxNanos = Math.max(highMaxNanos, nanos);
        } else if ("player".equals(queue)) {
            playerPackets += count;
            playerNanos += nanos;
            playerMaxNanos = Math.max(playerMaxNanos, nanos);
        } else if ("normal".equals(queue)) {
            normalPackets += count;
            normalNanos += nanos;
            normalMaxNanos = Math.max(normalMaxNanos, nanos);
        }
    }

    public static synchronized void recordWorldSection(String section, long nanos) {
        if (!ENABLED) return;
        if ("serverMapPre".equals(section)) {
            serverMapPreNanos += nanos;
            serverMapPreMaxNanos = Math.max(serverMapPreMaxNanos, nanos);
        } else if ("coreWorld".equals(section)) {
            coreWorldNanos += nanos;
            coreWorldMaxNanos = Math.max(coreWorldMaxNanos, nanos);
        } else if ("connectionChunk".equals(section)) {
            connectionChunkNanos += nanos;
            connectionChunkMaxNanos = Math.max(connectionChunkMaxNanos, nanos);
        } else if ("serverMapPost".equals(section)) {
            serverMapPostNanos += nanos;
            serverMapPostMaxNanos = Math.max(serverMapPostMaxNanos, nanos);
        }
    }

    public static synchronized void recordCoreWorldSection(String section, long nanos) {
        if (!ENABLED) return;
        if ("mapCollision".equals(section)) {
            mapCollisionNanos += nanos;
            mapCollisionMaxNanos = Math.max(mapCollisionMaxNanos, nanos);
        } else if ("stateUpdate".equals(section)) {
            stateUpdateNanos += nanos;
            stateUpdateMaxNanos = Math.max(stateUpdateMaxNanos, nanos);
        } else if ("vehicleUpdate".equals(section)) {
            vehicleUpdateNanos += nanos;
            vehicleUpdateMaxNanos = Math.max(vehicleUpdateMaxNanos, nanos);
        } else if ("objectId".equals(section)) {
            objectIdNanos += nanos;
            objectIdMaxNanos = Math.max(objectIdMaxNanos, nanos);
        }
    }

    public static synchronized void recordDownloadConnections(int count) {
        if (ENABLED) downloadConnections += count;
    }

    public static synchronized void recordWorldTick(long nanos) {
        if (!ENABLED) return;
        worldTicks++;
        worldNanos += nanos;
        worldMaxNanos = Math.max(worldMaxNanos, nanos);
    }

    public static synchronized void recordChunkMain(int waitingBefore, int requests, int preparedChunks, long nanos) {
        if (!ENABLED) return;
        chunkMainCalls++;
        chunkMainNanos += nanos;
        chunkMainMaxNanos = Math.max(chunkMainMaxNanos, nanos);
        chunkMainRequests += requests;
        chunkMainPrepared += preparedChunks;
        chunkMainMaxWaiting = Math.max(chunkMainMaxWaiting, waitingBefore);
    }

    public static synchronized void recordChunkWorker(int chunks, long nanos) {
        if (!ENABLED) return;
        chunkWorkerCalls++;
        chunkWorkerNanos += nanos;
        chunkWorkerMaxNanos = Math.max(chunkWorkerMaxNanos, nanos);
        chunkWorkerChunks += chunks;
    }

    public static synchronized void maybeLog() {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now < nextLogMs) return;
        DebugLog.log("[ApocBRTelemetry] intervalMs=" + INTERVAL_MS
            + " queues{high=" + highQueueLast + ",player=" + playerQueueLast + ",normal=" + normalQueueLast + "}"
            + " world{ticks=" + worldTicks + ",avgMs=" + avgMs(worldNanos, worldTicks) + ",maxMs=" + ms(worldMaxNanos)
            + ",serverMapPreAvgMs=" + avgMs(serverMapPreNanos, worldTicks) + ",serverMapPreMaxMs=" + ms(serverMapPreMaxNanos)
            + ",coreAvgMs=" + avgMs(coreWorldNanos, worldTicks) + ",coreMaxMs=" + ms(coreWorldMaxNanos)
            + ",mapCollisionAvgMs=" + avgMs(mapCollisionNanos, worldTicks) + ",mapCollisionMaxMs=" + ms(mapCollisionMaxNanos)
            + ",stateUpdateAvgMs=" + avgMs(stateUpdateNanos, worldTicks) + ",stateUpdateMaxMs=" + ms(stateUpdateMaxNanos)
            + ",vehicleAvgMs=" + avgMs(vehicleUpdateNanos, worldTicks) + ",vehicleMaxMs=" + ms(vehicleUpdateMaxNanos)
            + ",objectIdAvgMs=" + avgMs(objectIdNanos, worldTicks) + ",objectIdMaxMs=" + ms(objectIdMaxNanos)
            + ",connChunkAvgMs=" + avgMs(connectionChunkNanos, worldTicks) + ",connChunkMaxMs=" + ms(connectionChunkMaxNanos)
            + ",serverMapPostAvgMs=" + avgMs(serverMapPostNanos, worldTicks) + ",serverMapPostMaxMs=" + ms(serverMapPostMaxNanos) + "}"
            + " packets{high=" + highPackets + "/" + ms(highNanos) + "ms max=" + ms(highMaxNanos)
            + ",player=" + playerPackets + "/" + ms(playerNanos) + "ms max=" + ms(playerMaxNanos)
            + ",normal=" + normalPackets + "/" + ms(normalNanos) + "ms max=" + ms(normalMaxNanos) + "}"
            + " chunks{mainCalls=" + chunkMainCalls + ",mainAvgMs=" + avgMs(chunkMainNanos, chunkMainCalls) + ",mainMaxMs=" + ms(chunkMainMaxNanos)
            + ",requests=" + chunkMainRequests + ",prepared=" + chunkMainPrepared + ",maxWaiting=" + chunkMainMaxWaiting
            + ",workerCalls=" + chunkWorkerCalls + ",workerAvgMs=" + avgMs(chunkWorkerNanos, chunkWorkerCalls) + ",workerMaxMs=" + ms(chunkWorkerMaxNanos)
            + ",workerChunks=" + chunkWorkerChunks + ",downloadConnections=" + downloadConnections + "}"
            + " state{connections=" + connectionsLast + ",players=" + playersLast + ",zombies=" + zombiesLast + "}");
        resetCounters();
        nextLogMs = now + INTERVAL_MS;
    }

    private static void resetCounters() {
        highPackets = highNanos = highMaxNanos = 0L;
        playerPackets = playerNanos = playerMaxNanos = 0L;
        normalPackets = normalNanos = normalMaxNanos = 0L;
        worldTicks = worldNanos = worldMaxNanos = 0L;
        serverMapPreNanos = serverMapPreMaxNanos = 0L;
        coreWorldNanos = coreWorldMaxNanos = 0L;
        mapCollisionNanos = mapCollisionMaxNanos = 0L;
        stateUpdateNanos = stateUpdateMaxNanos = 0L;
        vehicleUpdateNanos = vehicleUpdateMaxNanos = 0L;
        objectIdNanos = objectIdMaxNanos = 0L;
        connectionChunkNanos = connectionChunkMaxNanos = 0L;
        serverMapPostNanos = serverMapPostMaxNanos = 0L;
        downloadConnections = 0L;
        chunkMainCalls = chunkMainNanos = chunkMainMaxNanos = 0L;
        chunkMainRequests = chunkMainPrepared = 0L;
        chunkMainMaxWaiting = 0;
        chunkWorkerCalls = chunkWorkerNanos = chunkWorkerMaxNanos = chunkWorkerChunks = 0L;
    }

    private static long ms(long nanos) { return nanos / 1000000L; }
    private static long avgMs(long nanos, long count) { return count <= 0L ? 0L : nanos / count / 1000000L; }
    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }

    private static long getLong(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(envKey(key));
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException ex) { return defaultValue; }
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(envKey(key));
        if (value == null || value.trim().isEmpty()) return defaultValue;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private static String envKey(String key) { return key.toUpperCase().replace('.', '_'); }
}