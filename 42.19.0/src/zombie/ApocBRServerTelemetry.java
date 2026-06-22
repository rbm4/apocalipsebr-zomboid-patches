package zombie;

import zombie.debug.DebugLog;

public final class ApocBRServerTelemetry {
    private static final boolean ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);
    private static final boolean PARALLEL_ISO_WORLD_SAFE = getBoolean("apocbr.parallel.isoWorldSafe", true);
    private static final boolean PARALLEL_SKIP_IF_BACKLOGGED = getBoolean("apocbr.parallel.skipIfBacklogged", true);
    private static final long PARALLEL_WARN_MS = clamp(getLong("apocbr.parallel.warnMs", 25L), 1L, 10000L);
    private static final String PARALLEL_ISO_WORLD_WORKERS = getString("apocbr.parallel.isoWorldWorkers", "auto");
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
    private static long serverMapPartitionNanos;
    private static long serverMapPartitionMaxNanos;
    private static long serverMapCellTasksNanos;
    private static long serverMapCellTasksMaxNanos;
    private static long serverMapMiscTasksNanos;
    private static long serverMapMiscTasksMaxNanos;
    private static long serverMapWaitNanos;
    private static long serverMapWaitMaxNanos;
    private static long serverMapCellsUpdated;
    private static int serverMapNumWorkersLast;
    private static int serverMapUnloadPendingLast;
    private static long serverMapUnloadQueued;
    private static long serverMapUnloadRevalidated;
    private static long serverMapUnloadCells;
    private static long serverMapUnloadNanos;
    private static long serverMapUnloadMaxNanos;
    private static long serverMapUnloadOldestAgeMsLast;
    private static long playerLOSComputeNanos;
    private static long playerLOSComputeMaxNanos;
    private static long playerLOSApplyNanos;
    private static long playerLOSApplyMaxNanos;
    private static long playerLOSObjects;
    private static long playerLOSCalls;
    private static long playerLOSParallel;
    private static long playerLOSSequential;
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
    private static long parallelWorldSubmitted;
    private static long parallelWorldSkipped;
    private static long parallelWorldWaitCalls;
    private static long parallelWorldWaitNanos;
    private static long parallelWorldWaitMaxNanos;
    private static long parallelWorldTaskCalls;
    private static long parallelWorldTaskNanos;
    private static long parallelWorldTaskMaxNanos;
    private static long parallelWorldErrors;
    private static long movingBucketCalls;
    private static long movingBucketObjects;
    private static long movingBucketZombies;
    private static long movingBucketNonZombies;
    private static long movingBucketDeadBodies;
    private static long movingBucketReusedZombies;
    private static long movingBucketPreupdateNanos;
    private static long movingBucketPreupdateMaxNanos;
    private static long movingBucketFrameStepNanos;
    private static long movingBucketFrameStepMaxNanos;
    private static long movingBucketUpdateNanos;
    private static long movingBucketUpdateMaxNanos;
    private static long movingBucketZombieUpdateNanos;
    private static long movingBucketZombieUpdateMaxNanos;
    private static long movingBucketNonZombieUpdateNanos;
    private static long movingBucketNonZombieUpdateMaxNanos;
    private static final int MOVING_TYPE_SLOTS = 6;
    private static final String[] movingTypeNames = new String[MOVING_TYPE_SLOTS];
    private static final long[] movingTypeCounts = new long[MOVING_TYPE_SLOTS];
    private static final long[] movingTypeUpdateNanos = new long[MOVING_TYPE_SLOTS];
    private static final long[] movingTypeUpdateMaxNanos = new long[MOVING_TYPE_SLOTS];
    private static long movingStartFrameCalls;
    private static long movingStartFrameObjects;
    private static long movingStartFrameNanos;
    private static long movingStartFrameMaxNanos;
    private static long movingStartFrameServerZombies;
    private static long movingStartFrameZombieGuiUpdates;
    private static long movingStartFrameZombieGuiNanos;
    private static long movingStartFrameZombieGuiMaxNanos;
    private static long movingStartFrameZombieOptimiserNanos;
    private static long movingStartFrameZombieOptimiserMaxNanos;
    private static long movingStartFrameSquareFixes;
    private static long movingStartFrameSquareFixNanos;
    private static long movingStartFrameSquareFixMaxNanos;
    private static long movingStartFrameBucketed;
    private static long movingStartFrameFull;
    private static long movingStartFrameHalf;
    private static long movingStartFrameQuarter;
    private static long movingStartFrameEighth;
    private static long movingStartFrameSixteenth;
    private static long movingAnimalFull;
    private static long movingAnimalHalf;
    private static long movingAnimalQuarter;
    private static long movingAnimalEighth;
    private static long movingAnimalSixteenth;
    private static long virtualAnimalChunks;
    private static long virtualAnimalChunksWithAnimals;
    private static long virtualAnimalChunksWithTracksOnly;
    private static long virtualAnimalUpdated;
    private static long virtualAnimalSkipped;
    private static long virtualAnimalTrackAdds;
    private static long virtualAnimalTrackSkips;
    private static long virtualAnimalTrackCleanupRuns;
    private static long virtualAnimalTracksRemoved;
    private static long virtualAnimalStateFollow;
    private static long virtualAnimalStateMove;
    private static long virtualAnimalStateEat;
    private static long virtualAnimalStateSleep;
    private static long virtualAnimalStateUnknown;
    private static final int MOVING_START_TYPE_SLOTS = 6;
    private static final String[] movingStartTypeNames = new String[MOVING_START_TYPE_SLOTS];
    private static final long[] movingStartTypeCounts = new long[MOVING_START_TYPE_SLOTS];
    private static long vehiclePartsCalls;
    private static long vehiclePartsNanos;
    private static long vehiclePartsMaxNanos;
    private static long vehiclePartCalls;
    private static long vehiclePartNanos;
    private static long vehiclePartMaxNanos;
    private static long vehiclePartLuaCalls;
    private static long vehiclePartLuaNanos;
    private static long vehiclePartLuaMaxNanos;
    private static long vehiclePartLuaSlowCalls;
    private static final String[] STATE_SECTION_KEYS = new String[] {
        "evenPausedLua", "isoWorld", "gem", "animal", "radio", "updateStuff", "onTickLua", "ambient", "updateManagers",
        "gameTime", "gameTimeMetaEvents", "gameTimeEveryDays", "gameTimeEveryHours", "gameTimeErosion", "gameTimeClimate", "gameTimeEveryTenMinutes", "gameTimeRadio", "gameTimeEveryOneMinute", "gameTimeSyncClock", "script", "worldSound", "fire", "rain", "meta", "virtualZombie", "mapCollisionMain",
        "zombiePopulationMain", "pathfindCheck", "pathfindMain", "polygonalMap", "lootRespawn", "serverManagers"
    };
    private static final long[] stateSectionNanos = new long[STATE_SECTION_KEYS.length];
    private static final long[] stateSectionMaxNanos = new long[STATE_SECTION_KEYS.length];
    private static final String[] ISO_WORLD_SECTION_KEYS = new String[] {
        "vehicleServer", "worldSimulation", "hutch", "fog", "helicopter", "emitters", "worldSoundFrame",
        "zombieGroupPre", "onceEvery", "collisionInit", "climate", "currentCell", "isoRegions", "haloText",
        "collisionResolve", "animationPost", "updateWorld", "updateInternal", "updateThread", "waitThread", "postUpdateWorld", "designationZone",
        "buildings", "staticEffects", "coopPlayers", "dbs", "safehouse", "virtualAnimals", "animalDefs"
    };
    private static final long[] isoWorldSectionNanos = new long[ISO_WORLD_SECTION_KEYS.length];
    private static final long[] isoWorldSectionMaxNanos = new long[ISO_WORLD_SECTION_KEYS.length];
    private static final String[] ISO_CELL_SECTION_KEYS = new String[] {
        "startFrame", "spottedRooms", "chunkMap", "removeItemsPre",
        "preLuaSubmit", "preLuaSkip",
        "isoObjectsMain", "staticUpdatersMain",
        "movingObjects", "animalSounds", "zombieVocals", "objects",
        "objectDeletionAddition",
        "postLuaSubmit", "postLuaSkip",
        "asyncItems", "asyncWorldItems", "asyncIsoObject", "asyncStaticUpdaters",
        "deadBodies", "fish", "lightCounters", "serverLightClear",
        "rainScroll", "weatherFx", "updateInternal"
    };
    private static final long[] isoCellSectionNanos = new long[ISO_CELL_SECTION_KEYS.length];
    private static final long[] isoCellSectionMaxNanos = new long[ISO_CELL_SECTION_KEYS.length];

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
        } else if ("serverMapPartition".equals(section)) {
            serverMapPartitionNanos += nanos;
            serverMapPartitionMaxNanos = Math.max(serverMapPartitionMaxNanos, nanos);
        } else if ("serverMapCellTasks".equals(section)) {
            serverMapCellTasksNanos += nanos;
            serverMapCellTasksMaxNanos = Math.max(serverMapCellTasksMaxNanos, nanos);
        } else if ("serverMapMiscTasks".equals(section)) {
            serverMapMiscTasksNanos += nanos;
            serverMapMiscTasksMaxNanos = Math.max(serverMapMiscTasksMaxNanos, nanos);
        } else if ("serverMapWait".equals(section)) {
            serverMapWaitNanos += nanos;
            serverMapWaitMaxNanos = Math.max(serverMapWaitMaxNanos, nanos);
        } else if ("playerLOSCompute".equals(section)) {
            playerLOSComputeNanos += nanos;
            playerLOSComputeMaxNanos = Math.max(playerLOSComputeMaxNanos, nanos);
        } else if ("playerLOSApply".equals(section)) {
            playerLOSApplyNanos += nanos;
            playerLOSApplyMaxNanos = Math.max(playerLOSApplyMaxNanos, nanos);
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
    public static synchronized void recordStateUpdateSection(String section, long nanos) {
        if (!ENABLED) return;
        for (int i = 0; i < STATE_SECTION_KEYS.length; i++) {
            if (STATE_SECTION_KEYS[i].equals(section)) {
                stateSectionNanos[i] += nanos;
                stateSectionMaxNanos[i] = Math.max(stateSectionMaxNanos[i], nanos);
                return;
            }
        }
    }

    public static synchronized void recordIsoWorldSection(String section, long nanos) {
        if (!ENABLED) return;
        for (int i = 0; i < ISO_WORLD_SECTION_KEYS.length; i++) {
            if (ISO_WORLD_SECTION_KEYS[i].equals(section)) {
                isoWorldSectionNanos[i] += nanos;
                isoWorldSectionMaxNanos[i] = Math.max(isoWorldSectionMaxNanos[i], nanos);
                return;
            }
        }
    }

    public static synchronized void recordIsoCellSection(String section, long nanos) {
        if (!ENABLED) return;
        for (int i = 0; i < ISO_CELL_SECTION_KEYS.length; i++) {
            if (ISO_CELL_SECTION_KEYS[i].equals(section)) {
                isoCellSectionNanos[i] += nanos;
                isoCellSectionMaxNanos[i] = Math.max(isoCellSectionMaxNanos[i], nanos);
                return;
            }
        }
    }

    public static boolean isParallelIsoWorldSafeEnabled() {
        return PARALLEL_ISO_WORLD_SAFE;
    }

    public static boolean shouldSkipParallelIsoWorldIfBacklogged() {
        return PARALLEL_SKIP_IF_BACKLOGGED;
    }

    public static long parallelWarnNanos() {
        return PARALLEL_WARN_MS * 1000000L;
    }

    public static synchronized void recordParallelWorldSubmitted() {
        if (ENABLED) parallelWorldSubmitted++;
    }

    public static synchronized void recordParallelWorldSkipped() {
        if (ENABLED) parallelWorldSkipped++;
    }

    public static synchronized void recordParallelWorldWait(long nanos) {
        if (!ENABLED) return;
        parallelWorldWaitCalls++;
        parallelWorldWaitNanos += nanos;
        parallelWorldWaitMaxNanos = Math.max(parallelWorldWaitMaxNanos, nanos);
    }

    public static synchronized void recordParallelWorldTask(long nanos) {
        if (!ENABLED) return;
        parallelWorldTaskCalls++;
        parallelWorldTaskNanos += nanos;
        parallelWorldTaskMaxNanos = Math.max(parallelWorldTaskMaxNanos, nanos);
    }

    public static synchronized void recordParallelWorldError() {
        if (ENABLED) parallelWorldErrors++;
    }

    public static synchronized void recordMovingStartFrame(int objectCount, long nanos) {
        if (!ENABLED) return;
        movingStartFrameCalls++;
        movingStartFrameObjects += objectCount;
        movingStartFrameNanos += nanos;
        movingStartFrameMaxNanos = Math.max(movingStartFrameMaxNanos, nanos);
    }

    public static synchronized void recordMovingStartFrameServerZombie(long guiNanos, long optimiserNanos) {
        if (!ENABLED) return;
        movingStartFrameServerZombies++;
        if (guiNanos > 0L) {
            movingStartFrameZombieGuiUpdates++;
            movingStartFrameZombieGuiNanos += guiNanos;
            movingStartFrameZombieGuiMaxNanos = Math.max(movingStartFrameZombieGuiMaxNanos, guiNanos);
        }
        movingStartFrameZombieOptimiserNanos += optimiserNanos;
        movingStartFrameZombieOptimiserMaxNanos = Math.max(movingStartFrameZombieOptimiserMaxNanos, optimiserNanos);
    }

    public static synchronized void recordMovingStartFrameSquareFix(long nanos) {
        if (!ENABLED) return;
        movingStartFrameSquareFixes++;
        movingStartFrameSquareFixNanos += nanos;
        movingStartFrameSquareFixMaxNanos = Math.max(movingStartFrameSquareFixMaxNanos, nanos);
    }

    public static synchronized void recordMovingStartFrameBucket(String typeName, String bucketName) {
        if (!ENABLED) return;
        movingStartFrameBucketed++;
        if ("full".equals(bucketName)) movingStartFrameFull++;
        else if ("half".equals(bucketName)) movingStartFrameHalf++;
        else if ("quarter".equals(bucketName)) movingStartFrameQuarter++;
        else if ("eighth".equals(bucketName)) movingStartFrameEighth++;
        else if ("sixteenth".equals(bucketName)) movingStartFrameSixteenth++;
        if ("IsoAnimal".equals(typeName)) {
            if ("full".equals(bucketName)) movingAnimalFull++;
            else if ("half".equals(bucketName)) movingAnimalHalf++;
            else if ("quarter".equals(bucketName)) movingAnimalQuarter++;
            else if ("eighth".equals(bucketName)) movingAnimalEighth++;
            else if ("sixteenth".equals(bucketName)) movingAnimalSixteenth++;
        }
        if (typeName == null || typeName.length() == 0) typeName = "Unknown";
        int slot = movingStartTypeSlot(typeName);
        movingStartTypeCounts[slot]++;
    }

    public static synchronized void recordVirtualAnimalChunk(boolean emptyAnimals, boolean hasTracks) {
        if (!ENABLED) return;
        virtualAnimalChunks++;
        if (!emptyAnimals) {
            virtualAnimalChunksWithAnimals++;
        } else if (hasTracks) {
            virtualAnimalChunksWithTracksOnly++;
        }
    }

    public static synchronized void recordServerMapDeferredUnload(
        int pending, int queued, int revalidated, int unloaded, long unloadNanos, long oldestAgeMs
    ) {
        if (!ENABLED) return;
        serverMapUnloadPendingLast = pending;
        serverMapUnloadQueued += queued;
        serverMapUnloadRevalidated += revalidated;
        serverMapUnloadCells += unloaded;
        serverMapUnloadNanos += unloadNanos;
        serverMapUnloadMaxNanos = Math.max(serverMapUnloadMaxNanos, unloadNanos);
        serverMapUnloadOldestAgeMsLast = oldestAgeMs;
    }

    public static synchronized void recordVirtualAnimalUpdate(String stateName, boolean skipped) {
        if (!ENABLED) return;
        if (skipped) {
            virtualAnimalSkipped++;
        } else {
            virtualAnimalUpdated++;
        }

        if ("Follow".equals(stateName)) {
            virtualAnimalStateFollow++;
        } else if ("MoveToEat".equals(stateName) || "MoveToSleep".equals(stateName) || "MoveFromEat".equals(stateName) || "MoveFromSleep".equals(stateName)) {
            virtualAnimalStateMove++;
        } else if ("Eat".equals(stateName)) {
            virtualAnimalStateEat++;
        } else if ("Sleep".equals(stateName)) {
            virtualAnimalStateSleep++;
        } else {
            virtualAnimalStateUnknown++;
        }
    }

    public static synchronized void recordVirtualAnimalTrackAttempt(boolean emitted) {
        if (!ENABLED) return;
        if (emitted) {
            virtualAnimalTrackAdds++;
        } else {
            virtualAnimalTrackSkips++;
        }
    }

    public static synchronized void recordVirtualAnimalTrackCleanup(long removed) {
        if (!ENABLED) return;
        virtualAnimalTrackCleanupRuns++;
        virtualAnimalTracksRemoved += removed;
    }

    public static synchronized void recordMovingBucketStart(int objectCount) {
        if (!ENABLED) return;
        movingBucketCalls++;
        movingBucketObjects += objectCount;
    }

    public static synchronized void recordMovingBucketDeadBody() {
        if (ENABLED) movingBucketDeadBodies++;
    }

    public static synchronized void recordMovingBucketReusedZombie() {
        if (ENABLED) movingBucketReusedZombies++;
    }

    public static synchronized void recordMovingBucketPreupdate(long nanos) {
        if (!ENABLED) return;
        movingBucketPreupdateNanos += nanos;
        movingBucketPreupdateMaxNanos = Math.max(movingBucketPreupdateMaxNanos, nanos);
    }

    public static synchronized void recordMovingBucketFrameStep(long nanos) {
        if (!ENABLED) return;
        movingBucketFrameStepNanos += nanos;
        movingBucketFrameStepMaxNanos = Math.max(movingBucketFrameStepMaxNanos, nanos);
    }

    public static synchronized void recordMovingBucketType(String typeName, long updateNanos) {
        if (!ENABLED) return;
        if (typeName == null || typeName.length() == 0) typeName = "Unknown";
        int slot = movingTypeSlot(typeName);
        movingTypeCounts[slot]++;
        movingTypeUpdateNanos[slot] += updateNanos;
        movingTypeUpdateMaxNanos[slot] = Math.max(movingTypeUpdateMaxNanos[slot], updateNanos);
    }

    public static synchronized void recordMovingBucketUpdate(boolean zombie, long nanos) {
        if (!ENABLED) return;
        movingBucketUpdateNanos += nanos;
        movingBucketUpdateMaxNanos = Math.max(movingBucketUpdateMaxNanos, nanos);
        if (zombie) {
            movingBucketZombies++;
            movingBucketZombieUpdateNanos += nanos;
            movingBucketZombieUpdateMaxNanos = Math.max(movingBucketZombieUpdateMaxNanos, nanos);
        } else {
            movingBucketNonZombies++;
            movingBucketNonZombieUpdateNanos += nanos;
            movingBucketNonZombieUpdateMaxNanos = Math.max(movingBucketNonZombieUpdateMaxNanos, nanos);
        }
    }

    public static synchronized void recordVehicleParts(long nanos) {
        if (!ENABLED) return;
        vehiclePartsCalls++;
        vehiclePartsNanos += nanos;
        vehiclePartsMaxNanos = Math.max(vehiclePartsMaxNanos, nanos);
    }

    public static synchronized void recordVehiclePart(long nanos) {
        if (!ENABLED) return;
        vehiclePartCalls++;
        vehiclePartNanos += nanos;
        vehiclePartMaxNanos = Math.max(vehiclePartMaxNanos, nanos);
    }

    public static synchronized void recordVehiclePartLua(long nanos) {
        if (!ENABLED) return;
        vehiclePartLuaCalls++;
        vehiclePartLuaNanos += nanos;
        vehiclePartLuaMaxNanos = Math.max(vehiclePartLuaMaxNanos, nanos);
        if (nanos >= 10000000L) vehiclePartLuaSlowCalls++;
    }
    public static synchronized void recordDownloadConnections(int count) {
        if (ENABLED) downloadConnections += count;
    }

    public static synchronized void recordServerMapCellsUpdated(int cells, int workers) {
        if (!ENABLED) return;
        serverMapCellsUpdated += cells;
        serverMapNumWorkersLast = Math.max(serverMapNumWorkersLast, workers);
    }

    public static synchronized void recordPlayerLOS(int objects, boolean isParallel, long computeNanos, long applyNanos) {
        if (!ENABLED) return;
        playerLOSCalls++;
        playerLOSObjects += objects;
        if (isParallel) playerLOSParallel++; else playerLOSSequential++;
        playerLOSComputeNanos += computeNanos;
        playerLOSComputeMaxNanos = Math.max(playerLOSComputeMaxNanos, computeNanos);
        playerLOSApplyNanos += applyNanos;
        playerLOSApplyMaxNanos = Math.max(playerLOSApplyMaxNanos, applyNanos);
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
            + ",serverMapPostAvgMs=" + avgMs(serverMapPostNanos, worldTicks) + ",serverMapPostMaxMs=" + ms(serverMapPostMaxNanos)
            + ",serverMapPartitionAvgMs=" + avgMs(serverMapPartitionNanos, worldTicks) + ",serverMapPartitionMaxMs=" + ms(serverMapPartitionMaxNanos)
            + ",serverMapCellTasksAvgMs=" + avgMs(serverMapCellTasksNanos, worldTicks) + ",serverMapCellTasksMaxMs=" + ms(serverMapCellTasksMaxNanos)
            + ",serverMapMiscTasksAvgMs=" + avgMs(serverMapMiscTasksNanos, worldTicks) + ",serverMapMiscTasksMaxMs=" + ms(serverMapMiscTasksMaxNanos)
            + ",serverMapWaitAvgMs=" + avgMs(serverMapWaitNanos, worldTicks) + ",serverMapWaitMaxMs=" + ms(serverMapWaitMaxNanos)
            + ",serverMapCellsUpdated=" + serverMapCellsUpdated + ",serverMapWorkers=" + serverMapNumWorkersLast
            + ",playerLOS=" + playerLOSCalls + ",playerLOSObjects=" + playerLOSObjects
            + ",playerLOSParallel=" + playerLOSParallel + ",playerLOSSequential=" + playerLOSSequential
            + ",playerLOSComputeAvgMs=" + avgMs(playerLOSComputeNanos, playerLOSCalls) + ",playerLOSComputeMaxMs=" + ms(playerLOSComputeMaxNanos)
            + ",playerLOSApplyAvgMs=" + avgMs(playerLOSApplyNanos, playerLOSCalls) + ",playerLOSApplyMaxMs=" + ms(playerLOSApplyMaxNanos) + "}"
            + " serverMapUnload{pending=" + serverMapUnloadPendingLast + ",queued=" + serverMapUnloadQueued
            + ",revalidated=" + serverMapUnloadRevalidated + ",unloaded=" + serverMapUnloadCells
            + ",avgMs=" + avgMs(serverMapUnloadNanos, serverMapUnloadCells) + ",maxMs=" + ms(serverMapUnloadMaxNanos)
            + ",oldestMs=" + serverMapUnloadOldestAgeMsLast + "}"
            + " packets{high=" + highPackets + "/" + ms(highNanos) + "ms max=" + ms(highMaxNanos)
            + ",player=" + playerPackets + "/" + ms(playerNanos) + "ms max=" + ms(playerMaxNanos)
            + ",normal=" + normalPackets + "/" + ms(normalNanos) + "ms max=" + ms(normalMaxNanos) + "}"
            + " chunks{mainCalls=" + chunkMainCalls + ",mainAvgMs=" + avgMs(chunkMainNanos, chunkMainCalls) + ",mainMaxMs=" + ms(chunkMainMaxNanos)
            + ",requests=" + chunkMainRequests + ",prepared=" + chunkMainPrepared + ",maxWaiting=" + chunkMainMaxWaiting
            + ",workerCalls=" + chunkWorkerCalls + ",workerAvgMs=" + avgMs(chunkWorkerNanos, chunkWorkerCalls) + ",workerMaxMs=" + ms(chunkWorkerMaxNanos)
            + ",workerChunks=" + chunkWorkerChunks + ",downloadConnections=" + downloadConnections + "}"
            + stateSectionsLog()
            + isoWorldSectionsLog()
            + isoCellSectionsLog()
            + parallelWorldLog()
            + movingBucketLog()
            + movingTypeLog()
            + movingStartFrameLog()
            + movingStartTypeLog()
            + movingVirtualAnimalLog()
            + movingAnimalBucketLog()
            + vehicleLog()
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
        serverMapPartitionNanos = serverMapPartitionMaxNanos = 0L;
        serverMapCellTasksNanos = serverMapCellTasksMaxNanos = 0L;
        serverMapMiscTasksNanos = serverMapMiscTasksMaxNanos = 0L;
        serverMapWaitNanos = serverMapWaitMaxNanos = 0L;
        serverMapCellsUpdated = 0L;
        serverMapNumWorkersLast = 0;
        serverMapUnloadPendingLast = 0;
        serverMapUnloadQueued = serverMapUnloadRevalidated = serverMapUnloadCells = 0L;
        serverMapUnloadNanos = serverMapUnloadMaxNanos = 0L;
        serverMapUnloadOldestAgeMsLast = 0L;
        playerLOSComputeNanos = playerLOSComputeMaxNanos = 0L;
        playerLOSApplyNanos = playerLOSApplyMaxNanos = 0L;
        playerLOSObjects = playerLOSCalls = playerLOSParallel = playerLOSSequential = 0L;
        chunkMainCalls = chunkMainNanos = chunkMainMaxNanos = 0L;
        chunkMainRequests = chunkMainPrepared = 0L;
        chunkMainMaxWaiting = 0;
        chunkWorkerCalls = chunkWorkerNanos = chunkWorkerMaxNanos = chunkWorkerChunks = 0L;
        parallelWorldSubmitted = parallelWorldSkipped = parallelWorldErrors = 0L;
        parallelWorldWaitCalls = parallelWorldWaitNanos = parallelWorldWaitMaxNanos = 0L;
        parallelWorldTaskCalls = parallelWorldTaskNanos = parallelWorldTaskMaxNanos = 0L;
        movingBucketCalls = movingBucketObjects = movingBucketZombies = movingBucketNonZombies = 0L;
        movingBucketDeadBodies = movingBucketReusedZombies = 0L;
        movingBucketPreupdateNanos = movingBucketPreupdateMaxNanos = 0L;
        movingBucketFrameStepNanos = movingBucketFrameStepMaxNanos = 0L;
        movingBucketUpdateNanos = movingBucketUpdateMaxNanos = 0L;
        movingBucketZombieUpdateNanos = movingBucketZombieUpdateMaxNanos = 0L;
        movingBucketNonZombieUpdateNanos = movingBucketNonZombieUpdateMaxNanos = 0L;
        movingStartFrameCalls = movingStartFrameObjects = movingStartFrameNanos = movingStartFrameMaxNanos = 0L;
        movingStartFrameServerZombies = movingStartFrameZombieGuiUpdates = 0L;
        movingStartFrameZombieGuiNanos = movingStartFrameZombieGuiMaxNanos = 0L;
        movingStartFrameZombieOptimiserNanos = movingStartFrameZombieOptimiserMaxNanos = 0L;
        movingStartFrameSquareFixes = movingStartFrameSquareFixNanos = movingStartFrameSquareFixMaxNanos = 0L;
        movingStartFrameBucketed = movingStartFrameFull = movingStartFrameHalf = movingStartFrameQuarter = movingStartFrameEighth = movingStartFrameSixteenth = 0L;
        movingAnimalFull = movingAnimalHalf = movingAnimalQuarter = movingAnimalEighth = movingAnimalSixteenth = 0L;
        virtualAnimalChunks = virtualAnimalChunksWithAnimals = virtualAnimalChunksWithTracksOnly = 0L;
        virtualAnimalUpdated = virtualAnimalSkipped = 0L;
        virtualAnimalTrackAdds = virtualAnimalTrackSkips = 0L;
        virtualAnimalTrackCleanupRuns = virtualAnimalTracksRemoved = 0L;
        virtualAnimalStateFollow = virtualAnimalStateMove = virtualAnimalStateEat = virtualAnimalStateSleep = virtualAnimalStateUnknown = 0L;
        vehiclePartsCalls = vehiclePartsNanos = vehiclePartsMaxNanos = 0L;
        vehiclePartCalls = vehiclePartNanos = vehiclePartMaxNanos = 0L;
        vehiclePartLuaCalls = vehiclePartLuaNanos = vehiclePartLuaMaxNanos = vehiclePartLuaSlowCalls = 0L;
        for (int i = 0; i < MOVING_START_TYPE_SLOTS; i++) {
            movingStartTypeNames[i] = null;
            movingStartTypeCounts[i] = 0L;
        }
        for (int i = 0; i < MOVING_TYPE_SLOTS; i++) {
            movingTypeNames[i] = null;
            movingTypeCounts[i] = 0L;
            movingTypeUpdateNanos[i] = 0L;
            movingTypeUpdateMaxNanos[i] = 0L;
        }
        for (int i = 0; i < STATE_SECTION_KEYS.length; i++) {
            stateSectionNanos[i] = 0L;
            stateSectionMaxNanos[i] = 0L;
        }
        for (int i = 0; i < ISO_WORLD_SECTION_KEYS.length; i++) {
            isoWorldSectionNanos[i] = 0L;
            isoWorldSectionMaxNanos[i] = 0L;
        }
        for (int i = 0; i < ISO_CELL_SECTION_KEYS.length; i++) {
            isoCellSectionNanos[i] = 0L;
            isoCellSectionMaxNanos[i] = 0L;
        }
    }

    private static String stateSectionsLog() {
        StringBuilder builder = new StringBuilder(" stateUpdate{");
        for (int i = 0; i < STATE_SECTION_KEYS.length; i++) {
            if (i > 0) builder.append(",");
            builder.append(STATE_SECTION_KEYS[i]).append("AvgMs=").append(avgMs(stateSectionNanos[i], worldTicks));
            builder.append(",").append(STATE_SECTION_KEYS[i]).append("MaxMs=").append(ms(stateSectionMaxNanos[i]));
        }
        builder.append("}");
        return builder.toString();
    }

    private static String isoWorldSectionsLog() {
        StringBuilder builder = new StringBuilder(" isoWorld{");
        for (int i = 0; i < ISO_WORLD_SECTION_KEYS.length; i++) {
            if (i > 0) builder.append(",");
            builder.append(ISO_WORLD_SECTION_KEYS[i]).append("AvgMs=").append(avgMs(isoWorldSectionNanos[i], worldTicks));
            builder.append(",").append(ISO_WORLD_SECTION_KEYS[i]).append("MaxMs=").append(ms(isoWorldSectionMaxNanos[i]));
        }
        builder.append("}");
        return builder.toString();
    }

    private static String isoCellSectionsLog() {
        StringBuilder builder = new StringBuilder(" isoCell{");
        for (int i = 0; i < ISO_CELL_SECTION_KEYS.length; i++) {
            if (i > 0) builder.append(",");
            builder.append(ISO_CELL_SECTION_KEYS[i]).append("AvgMs=").append(avgMs(isoCellSectionNanos[i], worldTicks));
            builder.append(",").append(ISO_CELL_SECTION_KEYS[i]).append("MaxMs=").append(ms(isoCellSectionMaxNanos[i]));
        }
        builder.append("}");
        return builder.toString();
    }

    private static String parallelWorldLog() {
        return " parallelWorld{enabled=" + PARALLEL_ISO_WORLD_SAFE
            + ",skipIfBacklogged=" + PARALLEL_SKIP_IF_BACKLOGGED
            + ",workers=" + PARALLEL_ISO_WORLD_WORKERS
            + ",submitted=" + parallelWorldSubmitted
            + ",skipped=" + parallelWorldSkipped
            + ",waitAvgMs=" + avgMs(parallelWorldWaitNanos, parallelWorldWaitCalls)
            + ",waitMaxMs=" + ms(parallelWorldWaitMaxNanos)
            + ",taskAvgMs=" + avgMs(parallelWorldTaskNanos, parallelWorldTaskCalls)
            + ",taskMaxMs=" + ms(parallelWorldTaskMaxNanos)
            + ",errors=" + parallelWorldErrors + "}";
    }

    private static int movingTypeSlot(String typeName) {
        int otherSlot = MOVING_TYPE_SLOTS - 1;
        if ("Other".equals(typeName)) {
            movingTypeNames[otherSlot] = "Other";
            return otherSlot;
        }

        for (int i = 0; i < otherSlot; i++) {
            if (typeName.equals(movingTypeNames[i])) return i;
        }

        for (int i = 0; i < otherSlot; i++) {
            if (movingTypeNames[i] == null) {
                movingTypeNames[i] = typeName;
                return i;
            }
        }

        movingTypeNames[otherSlot] = "Other";
        return otherSlot;
    }

    private static int movingStartTypeSlot(String typeName) {
        int otherSlot = MOVING_START_TYPE_SLOTS - 1;
        if ("Other".equals(typeName)) {
            movingStartTypeNames[otherSlot] = "Other";
            return otherSlot;
        }

        for (int i = 0; i < otherSlot; i++) {
            if (typeName.equals(movingStartTypeNames[i])) return i;
        }

        for (int i = 0; i < otherSlot; i++) {
            if (movingStartTypeNames[i] == null) {
                movingStartTypeNames[i] = typeName;
                return i;
            }
        }

        movingStartTypeNames[otherSlot] = "Other";
        return otherSlot;
    }

    private static String movingStartFrameLog() {
        return " movingStartFrame{calls=" + movingStartFrameCalls
            + ",objects=" + movingStartFrameObjects
            + ",avgObjects=" + avgCount(movingStartFrameObjects, movingStartFrameCalls)
            + ",avgMs=" + avgMs(movingStartFrameNanos, movingStartFrameCalls)
            + ",maxMs=" + ms(movingStartFrameMaxNanos)
            + ",serverZombies=" + movingStartFrameServerZombies
            + ",zombieGuiUpdates=" + movingStartFrameZombieGuiUpdates
            + ",zombieGuiAvgMs=" + avgMs(movingStartFrameZombieGuiNanos, movingStartFrameZombieGuiUpdates)
            + ",zombieGuiMaxMs=" + ms(movingStartFrameZombieGuiMaxNanos)
            + ",zombieOptimiserAvgMs=" + avgMs(movingStartFrameZombieOptimiserNanos, movingStartFrameServerZombies)
            + ",zombieOptimiserMaxMs=" + ms(movingStartFrameZombieOptimiserMaxNanos)
            + ",squareFixes=" + movingStartFrameSquareFixes
            + ",squareFixAvgMs=" + avgMs(movingStartFrameSquareFixNanos, movingStartFrameSquareFixes)
            + ",squareFixMaxMs=" + ms(movingStartFrameSquareFixMaxNanos)
            + ",bucketed=" + movingStartFrameBucketed
            + ",full=" + movingStartFrameFull
            + ",half=" + movingStartFrameHalf
            + ",quarter=" + movingStartFrameQuarter
            + ",eighth=" + movingStartFrameEighth
            + ",sixteenth=" + movingStartFrameSixteenth + "}";
    }

    private static String movingStartTypeLog() {
        StringBuilder builder = new StringBuilder(" movingStartTypes{");
        for (int i = 0; i < MOVING_START_TYPE_SLOTS; i++) {
            if (i > 0) builder.append(",");
            String name = movingStartTypeNames[i] == null ? "none" : movingStartTypeNames[i];
            builder.append("type").append(i).append("=").append(name);
            builder.append(",count").append(i).append("=").append(movingStartTypeCounts[i]);
        }
        builder.append("}");
        return builder.toString();
    }

    private static String movingAnimalBucketLog() {
        return " movingAnimalBuckets{full=" + movingAnimalFull
            + ",half=" + movingAnimalHalf
            + ",quarter=" + movingAnimalQuarter
            + ",eighth=" + movingAnimalEighth
            + ",sixteenth=" + movingAnimalSixteenth + "}";
    }

    private static String movingVirtualAnimalLog() {
        return " virtualAnimalSim{chunks=" + virtualAnimalChunks
            + ",chunksWithAnimals=" + virtualAnimalChunksWithAnimals
            + ",chunksWithTracksOnly=" + virtualAnimalChunksWithTracksOnly
            + ",updated=" + virtualAnimalUpdated
            + ",skipped=" + virtualAnimalSkipped
            + ",follow=" + virtualAnimalStateFollow
            + ",move=" + virtualAnimalStateMove
            + ",eat=" + virtualAnimalStateEat
            + ",sleep=" + virtualAnimalStateSleep
            + ",unknown=" + virtualAnimalStateUnknown
            + ",trackAdds=" + virtualAnimalTrackAdds
            + ",trackSkips=" + virtualAnimalTrackSkips
            + ",trackCleanupRuns=" + virtualAnimalTrackCleanupRuns
            + ",tracksRemoved=" + virtualAnimalTracksRemoved + "}";
    }

    private static String movingTypeLog() {
        StringBuilder builder = new StringBuilder(" movingTypes{");
        for (int i = 0; i < MOVING_TYPE_SLOTS; i++) {
            if (i > 0) builder.append(",");
            String name = movingTypeNames[i] == null ? "none" : movingTypeNames[i];
            builder.append("type").append(i).append("=").append(name);
            builder.append(",count").append(i).append("=").append(movingTypeCounts[i]);
            builder.append(",avgMs").append(i).append("=").append(avgMs(movingTypeUpdateNanos[i], movingTypeCounts[i]));
            builder.append(",maxMs").append(i).append("=").append(ms(movingTypeUpdateMaxNanos[i]));
        }
        builder.append("}");
        return builder.toString();
    }

    private static String vehicleLog() {
        return " vehicle{partsCalls=" + vehiclePartsCalls
            + ",partsAvgMs=" + avgMs(vehiclePartsNanos, vehiclePartsCalls)
            + ",partsMaxMs=" + ms(vehiclePartsMaxNanos)
            + ",partCalls=" + vehiclePartCalls
            + ",partAvgMs=" + avgMs(vehiclePartNanos, vehiclePartCalls)
            + ",partMaxMs=" + ms(vehiclePartMaxNanos)
            + ",luaCalls=" + vehiclePartLuaCalls
            + ",luaAvgMs=" + avgMs(vehiclePartLuaNanos, vehiclePartLuaCalls)
            + ",luaMaxMs=" + ms(vehiclePartLuaMaxNanos)
            + ",luaSlowCalls=" + vehiclePartLuaSlowCalls + "}";
    }
    private static String movingBucketLog() {
        return " movingBucket{calls=" + movingBucketCalls
            + ",objects=" + movingBucketObjects
            + ",avgObjects=" + avgCount(movingBucketObjects, movingBucketCalls)
            + ",zombies=" + movingBucketZombies
            + ",nonZombies=" + movingBucketNonZombies
            + ",deadBodies=" + movingBucketDeadBodies
            + ",reusedZombies=" + movingBucketReusedZombies
            + ",preupdateAvgMs=" + avgMs(movingBucketPreupdateNanos, movingBucketObjects)
            + ",preupdateMaxMs=" + ms(movingBucketPreupdateMaxNanos)
            + ",frameStepAvgMs=" + avgMs(movingBucketFrameStepNanos, movingBucketObjects)
            + ",frameStepMaxMs=" + ms(movingBucketFrameStepMaxNanos)
            + ",updateAvgMs=" + avgMs(movingBucketUpdateNanos, movingBucketZombies + movingBucketNonZombies)
            + ",updateMaxMs=" + ms(movingBucketUpdateMaxNanos)
            + ",zombieUpdateAvgMs=" + avgMs(movingBucketZombieUpdateNanos, movingBucketZombies)
            + ",zombieUpdateMaxMs=" + ms(movingBucketZombieUpdateMaxNanos)
            + ",nonZombieUpdateAvgMs=" + avgMs(movingBucketNonZombieUpdateNanos, movingBucketNonZombies)
            + ",nonZombieUpdateMaxMs=" + ms(movingBucketNonZombieUpdateMaxNanos) + "}";
    }

    private static long ms(long nanos) { return nanos / 1000000L; }
    private static long avgCount(long value, long count) { return count <= 0L ? 0L : value / count; }
    private static long avgMs(long nanos, long count) { return count <= 0L ? 0L : nanos / count / 1000000L; }
    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }

    private static long getLong(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(envKey(key));
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException ex) { return defaultValue; }
    }

    private static String getString(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(envKey(key));
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) value = System.getenv(envKey(key));
        if (value == null || value.trim().isEmpty()) return defaultValue;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private static String envKey(String key) { return key.toUpperCase().replace('.', '_'); }
}
