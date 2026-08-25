package zombie;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/**
 * Minimal server-side telemetry for ApocBR patches on build 42.20.1.
 *
 * Deliberately narrow in scope: players online, zombie count, server tick rate,
 * packet queue depth, deferred cell-unload cost, and the parallel ServerLOS
 * dispatcher. Do not add fields for subsystems that have not been
 * ported/patched yet (zombie network tiering, moving-object buckets, vehicle
 * Lua) - an always-zero telemetry field is worse than no field, since it
 * looks like a healthy signal.
 *
 * State (players/zombies/queues) is populated by {@link ApocBRTelemetrySampler}
 * via reflection against unmodified vanilla classes, on its own background
 * thread. World tick timing is recorded directly from the patched
 * {@code zombie.network.GameServer} main loop, since there is no vanilla field
 * that can be sampled externally to reconstruct real tick duration.
 *
 * Unload timing ("unload"/"unloadPhases" sections) is recorded directly at
 * the instrumented call sites in the patched {@code zombie.network.ServerMap}
 * ({@code processDeferredUnloads()}, {@code ServerCell.Unload(int)}) and the
 * patched {@code zombie.iso.IsoChunk} teardown split. Cell unload on 42.20.0
 * is time-sliced across ticks (ported from 42.19), bounded by a per-tick
 * cell/slice budget that escalates under backlog pressure - see the
 * DEFERRED_UNLOAD_MODE_* constants in ServerMap. "pending" tracks the current
 * unload backlog size; "oldestAgeMs" tracks how long the oldest queued cell
 * has been waiting, which is what drives escalation to WARNING/STRESS/
 * EMERGENCY mode. These are real per-tick/per-call measurements, not sampled
 * estimates.
 *
 * "los" is recorded directly at the instrumented call sites in the patched
 * {@code zombie.network.ServerLOS} (LOSDispatcher.runInner()/dispatch()).
 * Unlike the other sections above, these calc/nanos counters are written
 * concurrently from multiple PZForkJoinPool worker threads (one per LOS
 * slot), so they use lock-free {@link LongAdder}/{@link AtomicLong}/
 * {@link AtomicInteger} instead of the {@code synchronized} pattern used
 * elsewhere in this class, to avoid contending a shared lock on that hot
 * path. "slots" is the resolved concurrency ceiling (see
 * ServerLOS.LOS_SLOT_COUNT); "busyMax" is the highest number of slots seen
     * occupied at once during the interval, i.e. how close the dispatcher got to
 * saturating the pool; "starved" counts WaitingInLOS players that found no
 * free slot on a given dispatch pass (sustained non-zero starved is the
     * signal that this ceiling is now the bottleneck, not the vanilla local
     * co-op cap it replaced). "phased" is the deterministic 9-in-10 scheduler
     * delay before a player enters WaitingInLOS; "forced" counts first-run or
     * max-age escape hatches that bypass that throttle.
 */
public final class ApocBRServerTelemetry {
    private static final String PATCH_BUILD = "42.20.3";
    public static final boolean DETAIL_ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    public static final boolean PROD_ENABLED = getBoolean("apocbr.telemetry.prod", false);
    public static final boolean ENABLED = DETAIL_ENABLED || PROD_ENABLED;
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);
    private static final boolean NDJSON_ENABLED = getBoolean("apocbr.telemetry.ndjson.enabled", false);
    private static final String NDJSON_PATH = getString("apocbr.telemetry.ndjson.path", "apocbr-telemetry.ndjson");
    private static final int NDJSON_QUEUE_CAPACITY = (int)clamp(getLong("apocbr.telemetry.ndjson.queue", 64L), 1L, 4096L);
    private static final int LUA_TELEMETRY_TOP_N = (int)clamp(getLong("apocbr.telemetry.lua.topN", 16L), 1L, 64L);
    private static final long LUA_CALLBACK_SLOW_NANOS = clamp(getLong("apocbr.telemetry.lua.callbackSlowMs", 1L), 0L, 60000L) * 1000000L;
    private static final boolean LUA_CALLBACK_TELEMETRY_ENABLED = getBoolean("apocbr.telemetry.lua.callbacks.enabled", true);
    private static final AtomicBoolean startupBannerLogged = new AtomicBoolean(false);
    private static final ArrayBlockingQueue<String> ndjsonQueue = new ArrayBlockingQueue<>(NDJSON_QUEUE_CAPACITY);
    private static final AtomicBoolean ndjsonWriterStarted = new AtomicBoolean(false);
    private static final AtomicLong ndjsonSeq = new AtomicLong();
    private static final LongAdder ndjsonDropped = new LongAdder();

    private static final String[] OPTIMIZATION_PATCHES = new String[] {
        "Server main-loop telemetry and per-section tick timing",
        "ApocBR background sampler for player/zombie/connection/packet-queue state",
        "Telemetry NDJSON writer and production/detail JSON log modes",
        "Lua event/callback/direct-call timing instrumentation",
        "ServerMap load/recalc/post-update queue telemetry",
        "ServerMap single save worker and unload/save phase timing",
        "Incremental IsoChunk removeFromWorld teardown with per-square telemetry",
        "Lazy server container loading workaround for LoadGridsquare",
        "Parallel ServerLOS dispatcher, LOS throttling, slot accounting, and direct square lookup",
        "IsoGridSquare server lighting slot allocation and thread-local visibility scratch buffers",
        "Pathfind active chunk loadId registry and stale ChunkUpdateTask native-call guard",
        "Pathfind pathological request rejection before native calls",
        "Zombie population and indoor-spawn throttling/telemetry",
        "Zombie network list/manager/packer optimizations and telemetry",
        "Moving object update scheduler bucket instrumentation",
        "Entity/component update throttles and bucket self-healing checks",
        "ObjectID manager/type allocation safety instrumentation",
        "BodyDamageSync update throttle",
        "Vehicle network sound update throttle and connection guards",
        "ItemContainer and request-container packet performance safeguards"
    };

    private static long nextLogMs = System.currentTimeMillis() + INTERVAL_MS;

    private static long worldTicks;
    private static long worldNanos;
    private static long worldMaxNanos;

    private static int serverMapUnloadPendingLast;
    private static long serverMapUnloadQueued;
    private static long serverMapUnloadRevalidated;
    private static long serverMapUnloadCells;
    private static long serverMapUnloadNanos;
    private static long serverMapUnloadMaxNanos;
    private static long serverMapUnloadOldestAgeMsLast;
    private static int serverMapUnloadModeLast;
    private static int serverMapUnloadReadyLast;
    private static int serverMapUnloadMaxCellsLast;
    private static int serverMapUnloadSlicesLast;
    private static long serverMapUnloadAttempts;
    private static long serverMapUnloadPartialCells;
    private static int serverMapLoad2MaxCellsLast;
    private static int serverMapLoad2ReadyLast;
    private static int serverMapLoad2FlushedLast;
    private static int serverMapLoad2BacklogLast;
    private static long serverMapLoad2Attempts;
    private static long serverMapLoad2DeferredCells;
    private static final String[] SERVER_MAP_UNLOAD_PHASE_KEYS = new String[] {
        "chunkGlobal", "squareTeardown", "vehicleSave", "saveEnqueue"
    };
    private static final long[] serverMapUnloadPhaseCalls = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseUnits = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseNanos = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseMaxNanos = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final String[] SERVER_MAP_UNLOAD_DETAIL_KEYS = new String[] {
        "chunkMapCollision", "chunkAnimalPop", "chunkZombiePop", "chunkPathfind", "chunkCollisionClear",
        "squareRainWater", "squareRoomZone", "squareMoving", "squareObjects", "squareStatic",
        "squareAdjacent", "squareSoftClear", "finishVehicles", "finishChunkMeta", "saveUnloadedWrite",
        "reuseGridsquares", "zombieSaveCellSnapshot", "zombieSaveCellDeduped"
    };
    private static final LongAdder[] serverMapUnloadDetailCalls = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final LongAdder[] serverMapUnloadDetailUnits = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final LongAdder[] serverMapUnloadDetailNanos = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final AtomicLong[] serverMapUnloadDetailMaxNanos = newAtomicLongs(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);

    private static final String[] SERVER_MAP_PRE_KEYS = new String[] {
        "cancelScan", "collectPendingLoads", "sortPendingLoads", "addLoadJobs", "drainLoaded", "addRecalcJobs",
        "drainRecalc", "loadChunkCell", "loadChunkOne", "loadChunkSaveNow", "loadChunkWorldGen",
        "loadChunkForaging", "load2", "load2DrainRecalc", "load2MainPump", "load2MainTask",
        "load2PumpIdleWait", "load2RecalcAll2", "load2Vehicles",
        "removeLoaded2FromToLoad", "load2RoomsDec", "load2LevelScan", "load2EnsureSurround", "load2BorderRecalc",
        "load2MarkSquares", "load2DoLoadGridSquare", "load2NativeRegistrationBatch", "load2NativeMapCollision",
        "load2NativeAnimalPop", "load2NativeZombiePop", "load2NativePathfind", "load2IsoGenerator",
        "load2LootRespawn", "load2RoomsInc", "saveAll", "saveLater",
        "entitySave",
        // ApocBR: cross-tick load2. "load2" is now one in-tick slice per call rather than a whole
        // cell group, so these are what make the sliced pipeline legible:
        //   load2IdleAdvance   - slices run from the throttle-sleep window instead of the tick.
        //   load2JobComplete   - one call per finished job; units = slices it took, so avgUnits is
        //                        "ticks to load a cell group". This is the number to watch.
        //   load2StallCancel   - liveness guard fired; units = cells discarded. Should stay at zero.
        //   load2Anchor        - drains from tick-phase anchors; units = tasks applied.
        // load2LosSuspend/load2LosResume were removed: load2 no longer suspends LOS at all, and
        // leaving them emitting zeros would imply it still does.
        "load2IdleAdvance", "load2JobComplete", "load2StallCancel", "load2Anchor"
    };
    private static final LongAdder[] serverMapPreCalls = newLongAdders(SERVER_MAP_PRE_KEYS.length);
    private static final LongAdder[] serverMapPreUnits = newLongAdders(SERVER_MAP_PRE_KEYS.length);
    private static final LongAdder[] serverMapPreNanos = newLongAdders(SERVER_MAP_PRE_KEYS.length);
    private static final AtomicLong[] serverMapPreMaxNanos = newAtomicLongs(SERVER_MAP_PRE_KEYS.length);
    private static final String[] SERVER_MAP_POST_KEYS = new String[] {
        "loop", "relevantContains", "outsidePlayerInfluence", "cancelLoading", "losSuspend", "cellUnload",
        "cellMapClear", "loadedCellsRemove", "cellUpdate", "losResume", "zombiePost", "updateSaved"
    };
    private static final LongAdder[] serverMapPostCalls = newLongAdders(SERVER_MAP_POST_KEYS.length);
    private static final LongAdder[] serverMapPostUnits = newLongAdders(SERVER_MAP_POST_KEYS.length);
    private static final LongAdder[] serverMapPostNanos = newLongAdders(SERVER_MAP_POST_KEYS.length);
    private static final AtomicLong[] serverMapPostMaxNanos = newAtomicLongs(SERVER_MAP_POST_KEYS.length);
    private static final AtomicLong serverMapPreLoadQueueMax = new AtomicLong();
    private static final AtomicLong serverMapPreLoadedQueueMax = new AtomicLong();
    private static final AtomicLong serverMapPreRecalcQueueMax = new AtomicLong();
    private static final AtomicLong serverMapPreRecalcDoneQueueMax = new AtomicLong();
    private static final AtomicLong serverMapPreSaveQueueMax = new AtomicLong();
    private static volatile int serverMapPreLoadQueueLast;
    private static volatile int serverMapPreLoadedQueueLast;
    private static volatile int serverMapPreRecalcQueueLast;
    private static volatile int serverMapPreRecalcDoneQueueLast;
    private static volatile int serverMapPreSaveQueueLast;

    private static int playersLast;
    private static int zombiesLast;
    private static int connectionsLast;
    private static int highQueueLast;
    private static int playerQueueLast;
    private static int normalQueueLast;

    private static final LongAdder netHighPackets = new LongAdder();
    private static final LongAdder netHighNanos = new LongAdder();
    private static final AtomicLong netHighMaxNanos = new AtomicLong();
    private static final LongAdder netPlayerPackets = new LongAdder();
    private static final LongAdder netPlayerNanos = new LongAdder();
    private static final AtomicLong netPlayerMaxNanos = new AtomicLong();
    private static final LongAdder netNormalPackets = new LongAdder();
    private static final LongAdder netNormalProcessed = new LongAdder();
    private static final LongAdder netNormalDropped = new LongAdder();
    private static final LongAdder netNormalNanos = new LongAdder();
    private static final AtomicLong netNormalMaxNanos = new AtomicLong();

    private static volatile int losSlotCount;
    private static final AtomicInteger losSlotsBusy = new AtomicInteger();
    private static final AtomicInteger losSlotsBusyMax = new AtomicInteger();
    private static final LongAdder losCalcs = new LongAdder();
    private static final LongAdder losSkipped = new LongAdder();
    private static final LongAdder losPhased = new LongAdder();
    private static final LongAdder losForced = new LongAdder();
    private static final LongAdder losStarved = new LongAdder();
    private static final LongAdder losNanos = new LongAdder();
    private static final AtomicLong losMaxNanos = new AtomicLong();

    private static final LongAdder zombieAuthGridBuilds = new LongAdder();
    private static final LongAdder zombieAuthGridCells = new LongAdder();
    private static final LongAdder zombieAuthGridCandidates = new LongAdder();
    private static final LongAdder zombieAuthGridCellWrites = new LongAdder();
    private static final LongAdder zombieAuthGridNanos = new LongAdder();
    private static final AtomicLong zombieAuthGridMaxNanos = new AtomicLong();
    private static final LongAdder zombieAuthQueries = new LongAdder();
    private static final LongAdder zombieAuthQueryCandidates = new LongAdder();
    private static final LongAdder zombieAuthMoves = new LongAdder();
    private static final LongAdder zombieAuthUpdateCalls = new LongAdder();
    private static final LongAdder zombieAuthUpdateZombies = new LongAdder();
    private static final LongAdder zombieAuthUpdateNanos = new LongAdder();
    private static final AtomicLong zombieAuthUpdateMaxNanos = new AtomicLong();
    private static final LongAdder zombieAuthListCalls = new LongAdder();
    private static final LongAdder zombieAuthListNanos = new LongAdder();
    private static final AtomicLong zombieAuthListMaxNanos = new AtomicLong();

    private static final LongAdder zombieRelayGridBuilds = new LongAdder();
    private static final LongAdder zombieRelayGridActive = new LongAdder();
    private static final LongAdder zombieRelayGridCells = new LongAdder();
    private static final LongAdder zombieRelayGridNanos = new LongAdder();
    private static final AtomicLong zombieRelayGridMaxNanos = new AtomicLong();
    private static final LongAdder zombieRelayQueries = new LongAdder();
    private static final LongAdder zombieRelayCellsVisited = new LongAdder();
    private static final LongAdder zombieRelayCandidates = new LongAdder();
    private static final LongAdder zombieRelayInitialSent = new LongAdder();
    private static final LongAdder zombieRelaySent = new LongAdder();
    private static final LongAdder zombieRelayPackets = new LongAdder();
    private static final LongAdder zombieRelayExtraAllMarks = new LongAdder();
    private static final LongAdder zombieRelayExtraAllPackets = new LongAdder();
    private static final LongAdder zombieRelayPostCalls = new LongAdder();
    private static final LongAdder zombieRelayPostNanos = new LongAdder();
    private static final AtomicLong zombieRelayPostMaxNanos = new AtomicLong();
    private static final LongAdder zombieRelayConnectionCalls = new LongAdder();
    private static final LongAdder zombieRelayConnectionNanos = new LongAdder();
    private static final AtomicLong zombieRelayConnectionMaxNanos = new AtomicLong();
    private static final LongAdder zombieRelayGetDataNanos = new LongAdder();
    private static final AtomicLong zombieRelayGetDataMaxNanos = new AtomicLong();
    private static final LongAdder zombieRelaySendNanos = new LongAdder();
    private static final AtomicLong zombieRelaySendMaxNanos = new AtomicLong();

    private static final LongAdder zombieGroupGridBuilds = new LongAdder();
    private static final LongAdder zombieGroupGridGroups = new LongAdder();
    private static final LongAdder zombieGroupGridCells = new LongAdder();
    private static final LongAdder zombieGroupGridNanos = new LongAdder();
    private static final AtomicLong zombieGroupGridMaxNanos = new AtomicLong();
    private static final LongAdder zombieGroupQueries = new LongAdder();
    private static final LongAdder zombieGroupCandidates = new LongAdder();
    private static final LongAdder zombieGroupEmptyRemoved = new LongAdder();

    private static final LongAdder zombieServerUpdateCalls = new LongAdder();
    private static final LongAdder zombieServerUpdateOwned = new LongAdder();
    private static final LongAdder zombieServerUpdateTarget = new LongAdder();
    private static final LongAdder zombieServerUpdateRemote = new LongAdder();
    private static final LongAdder zombieServerUpdateNanos = new LongAdder();
    private static final AtomicLong zombieServerUpdateMaxNanos = new AtomicLong();
    private static final LongAdder zombiePopUpdates = new LongAdder();
    private static final LongAdder zombiePopNativeRequested = new LongAdder();
    private static final LongAdder zombiePopBatches = new LongAdder();
    private static final LongAdder zombiePopRecordsRead = new LongAdder();
    private static final LongAdder zombiePopSkippedNewIndoor = new LongAdder();
    private static final LongAdder zombiePopEdgeForcedStanding = new LongAdder();
    private static final LongAdder zombiePopStanding = new LongAdder();
    private static final LongAdder zombiePopMoving = new LongAdder();
    private static final LongAdder zombiePopNanos = new LongAdder();
    private static final AtomicLong zombiePopMaxNanos = new AtomicLong();

    private static final ConcurrentHashMap<String, DynamicTiming> luaEvents = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DynamicTiming> luaCallbacks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DynamicTiming> luaDirect = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DynamicTiming> mainThreadTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DynamicTiming> netHighPacketsByType = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DynamicTiming> netHighDetails = new ConcurrentHashMap<>();

    private static final String[] TICK_SECTION_KEYS = new String[] {
        "netHigh", "netPlayer", "netNormal", "throttleSleep", "removeRequests", "rcon",
        "mapCollision", "gameState", "vehicleManager", "objectIdManager", "playersRelevant", "importantAreas",
        "connectionRelevant", "objectCleanup", "connectionTimeouts", "serverMapPre", "serverMapPost",
        "serverMapCellUpdate", "serverMapZombiePost", "serverMapUpdateSaved", "serverGui", "consoleCommands",
        "statsPublic", "connectionMaintenance", "worldMapPositions", "coop", "loginQueue", "zipBackup",
        "steamLoop", "trading", "war", "safehouse", "networkPlayer", "asyncTransactions", "worldMapVisited",
        "stateEvenPausedLua", "stateIsoWorld", "stateGem", "stateAnimal", "stateRadio", "stateUpdateStuff",
        "stateOnTickLua", "stateAmbientWalls", "stateObjectAmbient", "stateModel", "stateUpdateManagers",
        "stateGameTime", "stateScript", "stateWorldSound", "stateFire", "stateRain", "stateMeta",
        "stateVirtualZombie", "stateMapCollisionMain", "stateZombiePopulationMain", "statePathfindCheck",
        "statePathfindMain", "statePolygonalMap", "stateLootRespawn", "stateServerManagers",
        "stateServerAmbient", "stateServerVehicleSound", "stateServerAnimEvent", "stateServerBodyDamage",
        "stateMoveStartFrame", "stateMoveUpdate", "stateMovePostUpdate",
        "stateIsoWorldVehicleServer", "stateIsoWorldSimulation", "stateIsoWorldHutch", "stateIsoWorldFogHelicopter",
        "stateIsoWorldEmitters", "stateIsoWorldZombieGroupPre", "stateIsoWorldCollisionInit", "stateIsoWorldClimate",
        "stateIsoWorldCell", "stateIsoWorldRegions", "stateIsoWorldHaloText", "stateIsoWorldCollisionResolve",
        "stateIsoWorldUpdateThread", "stateIsoWorldBuildings", "stateIsoWorldStaticEffects", "stateIsoWorldCoopPlayers",
        "stateIsoWorldDBs", "stateIsoWorldSafehousePlayers", "stateIsoWorldVirtualAnimals", "stateIsoWorldAnimalDefs",
        "stateIsoCellSpottedRooms", "stateIsoCellItems", "stateIsoCellIsoObject", "stateIsoCellObjects",
        "stateIsoCellSchedulerUpdate", "stateIsoCellAnimalVocals", "stateIsoCellZombieVocals",
        "stateIsoCellStaticUpdaters", "stateIsoCellObjectDeletion", "stateIsoCellDeadBodies", "stateIsoCellFish",
        "stateSearchMode", "stateRenderSettings"
    };
    private static final LongAdder[] tickSectionCalls = newLongAdders(TICK_SECTION_KEYS.length);
    private static final LongAdder[] tickSectionNanos = newLongAdders(TICK_SECTION_KEYS.length);
    private static final AtomicLong[] tickSectionMaxNanos = newAtomicLongs(TICK_SECTION_KEYS.length);

    private ApocBRServerTelemetry() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isDetailEnabled() {
        return DETAIL_ENABLED;
    }

    public static void logStartupBanner() {
        if (!startupBannerLogged.compareAndSet(false, true)) {
            return;
        }

        DebugLog.log("============================================================");
        DebugLog.log("[ApocBR] ApocalipseBR telemetry and optimizations patch loaded");
        DebugLog.log("[ApocBR] Target build: " + PATCH_BUILD);
        DebugLog.log("[ApocBR] Runtime properties:");
        logProperty("apocbr.telemetry.enabled", String.valueOf(DETAIL_ENABLED), "true");
        logProperty("apocbr.telemetry.prod", String.valueOf(PROD_ENABLED), "false");
        logProperty("apocbr.telemetry.intervalMs", String.valueOf(INTERVAL_MS), "30000");
        logProperty("apocbr.telemetry.sampleIntervalMs", String.valueOf(clamp(getLong("apocbr.telemetry.sampleIntervalMs", 5000L), 1000L, 60000L)), "5000");
        logProperty("apocbr.telemetry.ndjson.enabled", String.valueOf(NDJSON_ENABLED), "false");
        logProperty("apocbr.telemetry.ndjson.path", NDJSON_PATH, "apocbr-telemetry.ndjson");
        logProperty("apocbr.telemetry.ndjson.queue", String.valueOf(NDJSON_QUEUE_CAPACITY), "64");
        logProperty("apocbr.telemetry.lua.topN", String.valueOf(LUA_TELEMETRY_TOP_N), "16");
        logProperty("apocbr.telemetry.lua.callbackSlowMs", String.valueOf(LUA_CALLBACK_SLOW_NANOS / 1000000L), "1");
        logProperty("apocbr.telemetry.lua.callbacks.enabled", String.valueOf(LUA_CALLBACK_TELEMETRY_ENABLED), "true");
        logProperty("apocbr.vehicleSoundUpdateIntervalTicks", String.valueOf(Math.max(1, Integer.getInteger("apocbr.vehicleSoundUpdateIntervalTicks", 5))), "5");
        logProperty("apocbr.bodyDamageSyncUpdateIntervalTicks", String.valueOf(Math.max(1, Integer.getInteger("apocbr.bodyDamageSyncUpdateIntervalTicks", 5))), "5");
        logProperty("apocbr.lazyServerContainerLoad", String.valueOf(getBoolean("apocbr.lazyServerContainerLoad", true)), "true");
        DebugLog.log("[ApocBR] Implemented optimization patches:");
        for (String patch : OPTIMIZATION_PATCHES) {
            DebugLog.log("[ApocBR]   - " + patch);
        }
        DebugLog.log("============================================================");
    }

    public static long beginDetail() {
        return DETAIL_ENABLED ? System.nanoTime() : 0L;
    }

    public static synchronized void recordWorldTick(long nanos) {
        if (!ENABLED) return;
        worldTicks++;
        worldNanos += nanos;
        worldMaxNanos = Math.max(worldMaxNanos, nanos);
    }

    public static void recordTickSection(String section, long nanos) {
        if (!DETAIL_ENABLED || nanos < 0L) return;
        for (int i = 0; i < TICK_SECTION_KEYS.length; i++) {
            if (TICK_SECTION_KEYS[i].equals(section)) {
                tickSectionCalls[i].increment();
                tickSectionNanos[i].add(nanos);
                tickSectionMaxNanos[i].accumulateAndGet(nanos, Math::max);
                return;
            }
        }
    }

    public static void recordTickSectionSince(String section, long startNanos) {
        if (!DETAIL_ENABLED) return;
        recordTickSection(section, System.nanoTime() - startNanos);
    }

    public static void recordLuaEvent(String event, int callbackCount, long nanos) {
        if (!DETAIL_ENABLED || event == null || nanos < 0L) return;
        recordDynamicTiming(luaEvents, event, Math.max(0, callbackCount), nanos);
    }

    public static void recordLuaCallback(String event, String callback, long nanos) {
        if (!DETAIL_ENABLED || !LUA_CALLBACK_TELEMETRY_ENABLED || event == null || callback == null || nanos < LUA_CALLBACK_SLOW_NANOS) return;
        recordDynamicTiming(luaCallbacks, event + "|" + callback, 1, nanos);
    }

    public static void recordLuaDirect(String callsite, long nanos) {
        if (!DETAIL_ENABLED || callsite == null || nanos < 0L) return;
        recordDynamicTiming(luaDirect, callsite, 1, nanos);
    }

    public static void recordMainThreadTaskSubmitted(String label) {
        if (!DETAIL_ENABLED || label == null) return;
        recordDynamicTiming(mainThreadTasks, label + "|submitted", 1, 0L);
    }

    public static void recordMainThreadTaskDrained(String label, long nanos) {
        if (!DETAIL_ENABLED || label == null || nanos < 0L) return;
        recordDynamicTiming(mainThreadTasks, label, 1, nanos);
    }

    public static void recordMainLoopNetHighPacket(String packetType, long nanos) {
        if (!DETAIL_ENABLED || packetType == null || nanos < 0L) return;
        recordDynamicTiming(netHighPacketsByType, packetType, 1, nanos);
    }

    public static void recordNetHighDetail(String detail, int units, long nanos) {
        if (!DETAIL_ENABLED || detail == null || nanos < 0L) return;
        recordDynamicTiming(netHighDetails, detail, Math.max(0, units), nanos);
    }

    private static void recordDynamicTiming(ConcurrentHashMap<String, DynamicTiming> map, String key, int units, long nanos) {
        DynamicTiming timing = map.computeIfAbsent(key, ignored -> new DynamicTiming());
        timing.calls.increment();
        timing.units.add(units);
        timing.nanos.add(nanos);
        timing.maxNanos.accumulateAndGet(nanos, Math::max);
    }

    /**
     * Real per-tick accounting for the deferred/time-sliced cell unload
     * mechanism in the patched {@code zombie.network.ServerMap}. Called once
     * per {@code postupdate()} tick from {@code processDeferredUnloads()}.
     */
    public static synchronized void recordServerMapDeferredUnload(
        int pending, int queued, int revalidated, int unloaded, long unloadNanos, long oldestAgeMs
    ) {
        if (!DETAIL_ENABLED) return;
        serverMapUnloadPendingLast = pending;
        serverMapUnloadQueued += queued;
        serverMapUnloadRevalidated += revalidated;
        serverMapUnloadCells += unloaded;
        serverMapUnloadNanos += unloadNanos;
        serverMapUnloadMaxNanos = Math.max(serverMapUnloadMaxNanos, unloadNanos);
        serverMapUnloadOldestAgeMsLast = oldestAgeMs;
    }

    public static synchronized void recordServerMapDeferredUnloadBudget(
        int mode, int ready, int maxCells, int slicesPerTick, int attempts, int partialCells
    ) {
        if (!DETAIL_ENABLED) return;
        serverMapUnloadModeLast = mode;
        serverMapUnloadReadyLast = ready;
        serverMapUnloadMaxCellsLast = maxCells;
        serverMapUnloadSlicesLast = slicesPerTick;
        serverMapUnloadAttempts += attempts;
        serverMapUnloadPartialCells += partialCells;
    }

    /**
     * Per-tick cap on how many ready cells Load2 prep/finalization flushes in a
     * single tick, mirroring the deferred-unload budget schema above. ready
     * is how many cells were sitting in loaded2 when the loop started,
     * flushed is how many actually got processed this tick (bounded by
     * maxCells), and backlogAfter is what's left over for the next tick.
     */
    public static synchronized void recordServerMapLoad2Budget(int maxCells, int ready, int flushed, int backlogAfter) {
        if (!DETAIL_ENABLED) return;
        serverMapLoad2MaxCellsLast = maxCells;
        serverMapLoad2ReadyLast = ready;
        serverMapLoad2FlushedLast = flushed;
        serverMapLoad2BacklogLast = backlogAfter;
        serverMapLoad2Attempts++;
        if (backlogAfter > 0) {
            serverMapLoad2DeferredCells += backlogAfter;
        }
    }

    /**
     * Per-phase breakdown of unload cost (chunkGlobal = collision/pathfind
     * removal done once per chunk; squareTeardown = the time-sliced per-square
     * loop; vehicleSave/saveEnqueue = post-teardown bookkeeping). Lets us see
     * which phase dominates instead of only the aggregate unload cost.
     */
    public static synchronized void recordServerMapUnloadPhase(String phase, int units, long nanos) {
        if (!DETAIL_ENABLED) return;
        for (int i = 0; i < SERVER_MAP_UNLOAD_PHASE_KEYS.length; i++) {
            if (SERVER_MAP_UNLOAD_PHASE_KEYS[i].equals(phase)) {
                serverMapUnloadPhaseCalls[i]++;
                serverMapUnloadPhaseUnits[i] += units;
                serverMapUnloadPhaseNanos[i] += nanos;
                serverMapUnloadPhaseMaxNanos[i] = Math.max(serverMapUnloadPhaseMaxNanos[i], nanos);
                return;
            }
        }
    }

    public static void recordServerMapUnloadDetail(String detail, int units, long nanos) {
        if (!DETAIL_ENABLED) return;
        for (int i = 0; i < SERVER_MAP_UNLOAD_DETAIL_KEYS.length; i++) {
            if (SERVER_MAP_UNLOAD_DETAIL_KEYS[i].equals(detail)) {
                serverMapUnloadDetailCalls[i].increment();
                serverMapUnloadDetailUnits[i].add(units);
                serverMapUnloadDetailNanos[i].add(nanos);
                serverMapUnloadDetailMaxNanos[i].accumulateAndGet(nanos, Math::max);
                return;
            }
        }
    }

    public static void recordServerMapPrePhase(String phase, int units, long nanos) {
        if (!DETAIL_ENABLED || nanos < 0L) return;
        for (int i = 0; i < SERVER_MAP_PRE_KEYS.length; i++) {
            if (SERVER_MAP_PRE_KEYS[i].equals(phase)) {
                serverMapPreCalls[i].increment();
                serverMapPreUnits[i].add(units);
                serverMapPreNanos[i].add(nanos);
                serverMapPreMaxNanos[i].accumulateAndGet(nanos, Math::max);
                return;
            }
        }
    }

    public static void recordServerMapPrePhaseSince(String phase, int units, long startNanos) {
        if (!DETAIL_ENABLED) return;
        recordServerMapPrePhase(phase, units, System.nanoTime() - startNanos);
    }

    public static void recordServerMapPostPhase(String phase, int units, long nanos) {
        if (!DETAIL_ENABLED || nanos < 0L) return;
        for (int i = 0; i < SERVER_MAP_POST_KEYS.length; i++) {
            if (SERVER_MAP_POST_KEYS[i].equals(phase)) {
                serverMapPostCalls[i].increment();
                serverMapPostUnits[i].add(units);
                serverMapPostNanos[i].add(nanos);
                serverMapPostMaxNanos[i].accumulateAndGet(nanos, Math::max);
                return;
            }
        }
    }

    public static void recordServerMapPostPhaseSince(String phase, int units, long startNanos) {
        if (!DETAIL_ENABLED) return;
        recordServerMapPostPhase(phase, units, System.nanoTime() - startNanos);
    }

    public static void recordServerMapPreQueues(int loadQueue, int loadedQueue, int recalcQueue, int recalcDoneQueue, int saveQueue) {
        if (!DETAIL_ENABLED) return;
        serverMapPreLoadQueueLast = loadQueue;
        serverMapPreLoadedQueueLast = loadedQueue;
        serverMapPreRecalcQueueLast = recalcQueue;
        serverMapPreRecalcDoneQueueLast = recalcDoneQueue;
        serverMapPreSaveQueueLast = saveQueue;
        serverMapPreLoadQueueMax.accumulateAndGet(loadQueue, Math::max);
        serverMapPreLoadedQueueMax.accumulateAndGet(loadedQueue, Math::max);
        serverMapPreRecalcQueueMax.accumulateAndGet(recalcQueue, Math::max);
        serverMapPreRecalcDoneQueueMax.accumulateAndGet(recalcDoneQueue, Math::max);
        serverMapPreSaveQueueMax.accumulateAndGet(saveQueue, Math::max);
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

    public static void recordMainLoopNetHigh(int packets, long nanos) {
        if (!DETAIL_ENABLED) return;
        netHighPackets.add(packets);
        netHighNanos.add(nanos);
        netHighMaxNanos.accumulateAndGet(nanos, Math::max);
        recordTickSection("netHigh", nanos);
    }

    public static void recordMainLoopNetPlayer(int packets, long nanos) {
        if (!DETAIL_ENABLED) return;
        netPlayerPackets.add(packets);
        netPlayerNanos.add(nanos);
        netPlayerMaxNanos.accumulateAndGet(nanos, Math::max);
        recordTickSection("netPlayer", nanos);
    }

    public static void recordMainLoopNetNormal(int packets, int processed, int dropped, long nanos) {
        if (!DETAIL_ENABLED) return;
        netNormalPackets.add(packets);
        netNormalProcessed.add(processed);
        netNormalDropped.add(dropped);
        netNormalNanos.add(nanos);
        netNormalMaxNanos.accumulateAndGet(nanos, Math::max);
        recordTickSection("netNormal", nanos);
    }

    /**
     * Resolved LOS concurrency ceiling (see ServerLOS.LOS_SLOT_COUNT). Called once from
     * ServerLOS.start() - a plain volatile write is enough since this never changes again.
     */
    public static void recordServerLosSlotCount(int slotCount) {
        losSlotCount = slotCount;
    }

    /**
     * Called from the LOS dispatcher thread right after it successfully claims a free slot
     * for a WaitingInLOS player, before handing the calc off to PZForkJoinPool.
     */
    public static void recordServerLosDispatch() {
        if (!DETAIL_ENABLED) return;
        int busy = losSlotsBusy.incrementAndGet();
        losSlotsBusyMax.accumulateAndGet(busy, Math::max);
    }

    /**
     * Called from the LOS dispatcher thread when a WaitingInLOS player found no free slot on
     * this pass (the pool is fully saturated). Sustained non-zero values mean LOS_SLOT_COUNT
     * is the current bottleneck.
     */
    public static void recordServerLosStarved() {
        if (!DETAIL_ENABLED) return;
        losStarved.increment();
    }

    public static void recordServerLosPhased() {
        if (!DETAIL_ENABLED) return;
        losPhased.increment();
    }

    public static void recordServerLosForced() {
        if (!DETAIL_ENABLED) return;
        losForced.increment();
    }

    /**
     * Called from a PZForkJoinPool worker thread once a single player's calcLOS() call
     * finishes (in ServerLOS.dispatch()'s finally block). skipped mirrors calcLOS()'s own
     * fast path (player hasn't moved grid cell since last calc) - nanos is 0 in that case
     * since no square work was done.
     */
    public static void recordServerLosCalc(boolean skipped, long nanos) {
        if (!DETAIL_ENABLED) return;
        losSlotsBusy.decrementAndGet();
        if (skipped) {
            losSkipped.increment();
        } else {
            losCalcs.increment();
            losNanos.add(nanos);
            losMaxNanos.accumulateAndGet(nanos, Math::max);
        }
    }

    public static void recordZombieAuthGrid(int cells, int candidates, int cellWrites, long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieAuthGridBuilds.increment();
        zombieAuthGridCells.add(cells);
        zombieAuthGridCandidates.add(candidates);
        zombieAuthGridCellWrites.add(cellWrites);
        zombieAuthGridNanos.add(nanos);
        zombieAuthGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieAuthQuery(int candidates) {
        if (!DETAIL_ENABLED) return;
        zombieAuthQueries.increment();
        zombieAuthQueryCandidates.add(candidates);
    }

    public static void recordZombieAuthMove() {
        if (!DETAIL_ENABLED) return;
        zombieAuthMoves.increment();
    }

    public static void recordZombieAuthUpdate(int zombies, long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieAuthUpdateCalls.increment();
        zombieAuthUpdateZombies.add(zombies);
        zombieAuthUpdateNanos.add(nanos);
        zombieAuthUpdateMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieAuthList(long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieAuthListCalls.increment();
        zombieAuthListNanos.add(nanos);
        zombieAuthListMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayGrid(int activeZombies, int cells, long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieRelayGridBuilds.increment();
        zombieRelayGridActive.add(activeZombies);
        zombieRelayGridCells.add(cells);
        zombieRelayGridNanos.add(nanos);
        zombieRelayGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayQuery(int cellsVisited, int candidates, int sent) {
        if (!DETAIL_ENABLED) return;
        zombieRelayQueries.increment();
        zombieRelayCellsVisited.add(cellsVisited);
        zombieRelayCandidates.add(candidates);
        zombieRelaySent.add(sent);
    }

    public static void recordZombieRelayInitial(int sent) {
        if (!DETAIL_ENABLED) return;
        zombieRelayInitialSent.add(sent);
    }

    public static void recordZombieRelayPacket(boolean extraAll) {
        if (!DETAIL_ENABLED) return;
        zombieRelayPackets.increment();
        if (extraAll) {
            zombieRelayExtraAllPackets.increment();
        }
    }

    public static void recordZombieRelayExtraAllMark() {
        if (!DETAIL_ENABLED) return;
        zombieRelayExtraAllMarks.increment();
    }

    public static void recordZombieRelayPost(long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieRelayPostCalls.increment();
        zombieRelayPostNanos.add(nanos);
        zombieRelayPostMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayConnection(long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieRelayConnectionCalls.increment();
        zombieRelayConnectionNanos.add(nanos);
        zombieRelayConnectionMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayGetData(long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieRelayGetDataNanos.add(nanos);
        zombieRelayGetDataMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelaySend(long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieRelaySendNanos.add(nanos);
        zombieRelaySendMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieGroupGrid(int groups, int cells, long nanos) {
        if (!DETAIL_ENABLED) return;
        zombieGroupGridBuilds.increment();
        zombieGroupGridGroups.add(groups);
        zombieGroupGridCells.add(cells);
        zombieGroupGridNanos.add(nanos);
        zombieGroupGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieGroupQuery(int candidates, boolean removedEmptyGroups) {
        if (!DETAIL_ENABLED) return;
        zombieGroupQueries.increment();
        zombieGroupCandidates.add(candidates);
        if (removedEmptyGroups) {
            zombieGroupEmptyRemoved.increment();
        }
    }

    public static void recordZombieServerUpdate(long nanos, boolean owned, boolean hasTarget, boolean remote) {
        if (!DETAIL_ENABLED) return;
        zombieServerUpdateCalls.increment();
        if (owned) {
            zombieServerUpdateOwned.increment();
        }
        if (hasTarget) {
            zombieServerUpdateTarget.increment();
        }
        if (remote) {
            zombieServerUpdateRemote.increment();
        }
        zombieServerUpdateNanos.add(nanos);
        zombieServerUpdateMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombiePopulationUpdate(
        int nativeRequested,
        int batches,
        int recordsRead,
        int skippedNewIndoor,
        int edgeForcedStanding,
        int standing,
        int moving,
        long nanos
    ) {
        if (!DETAIL_ENABLED) return;
        zombiePopUpdates.increment();
        zombiePopNativeRequested.add(nativeRequested);
        zombiePopBatches.add(batches);
        zombiePopRecordsRead.add(recordsRead);
        zombiePopSkippedNewIndoor.add(skippedNewIndoor);
        zombiePopEdgeForcedStanding.add(edgeForcedStanding);
        zombiePopStanding.add(standing);
        zombiePopMoving.add(moving);
        zombiePopNanos.add(nanos);
        zombiePopMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static synchronized void maybeLog() {
        if (!ENABLED) return;
        long now = System.currentTimeMillis();
        if (now < nextLogMs) return;
        String payload = DETAIL_ENABLED ? buildJsonPayload(now) : buildProdJsonPayload(now);
        DebugLog.log("[ApocBRTelemetry]" + payload);
        offerNdjson(payload);
        resetWorldCounters();
        nextLogMs = now + INTERVAL_MS;
    }

    private static String buildProdJsonPayload(long now) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schemaVersion\":1");
        json.append(",\"seq\":").append(ndjsonSeq.incrementAndGet());
        json.append(",\"ts\":").append(now);
        json.append(",\"mode\":\"prod\"");
        json.append(",\"ndjson\":{\"dropped\":").append(ndjsonDropped.sum()).append("}");
        json.append(",\"world\":{\"ticks\":").append(worldTicks)
            .append(",\"avgMs\":").append(avgMs(worldNanos, worldTicks))
            .append(",\"maxMs\":").append(ms(worldMaxNanos))
            .append("}");
        json.append(",\"state\":{\"players\":").append(playersLast)
            .append(",\"zombies\":").append(zombiesLast)
            .append(",\"connections\":").append(connectionsLast)
            .append(",\"queues\":{\"high\":").append(highQueueLast)
            .append(",\"player\":").append(playerQueueLast)
            .append(",\"normal\":").append(normalQueueLast)
            .append("}}");
        json.append("}");
        return json.toString();
    }

    private static String buildJsonPayload(long now) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"schemaVersion\":1");
        json.append(",\"seq\":").append(ndjsonSeq.incrementAndGet());
        json.append(",\"ts\":").append(now);
        json.append(",\"mode\":\"detail\"");
        json.append(",\"ndjson\":{\"dropped\":").append(ndjsonDropped.sum()).append("}");
        json.append(",\"world\":{\"ticks\":").append(worldTicks)
            .append(",\"avgMs\":").append(avgMs(worldNanos, worldTicks))
            .append(",\"maxMs\":").append(ms(worldMaxNanos))
            .append("}");
        json.append(",\"tickSections\":{");
        for (int i = 0; i < TICK_SECTION_KEYS.length; i++) {
            long calls = tickSectionCalls[i].sum();
            if (i > 0) json.append(",");
            json.append("\"").append(TICK_SECTION_KEYS[i]).append("\":{\"calls\":").append(calls)
                .append(",\"avgMs\":").append(avgMs(tickSectionNanos[i].sum(), worldTicks))
                .append(",\"avgCallMs\":").append(avgMs(tickSectionNanos[i].sum(), calls))
                .append(",\"maxMs\":").append(ms(tickSectionMaxNanos[i].get()))
                .append("}");
        }
        json.append("}");
        json.append(",\"unload\":{\"pending\":").append(serverMapUnloadPendingLast)
            .append(",\"queued\":").append(serverMapUnloadQueued)
            .append(",\"revalidated\":").append(serverMapUnloadRevalidated)
            .append(",\"cells\":").append(serverMapUnloadCells)
            .append(",\"avgMs\":").append(avgMs(serverMapUnloadNanos, serverMapUnloadCells))
            .append(",\"maxMs\":").append(ms(serverMapUnloadMaxNanos))
            .append(",\"oldestAgeMs\":").append(serverMapUnloadOldestAgeMsLast)
            .append("}");
        json.append(",\"unloadBudget\":{\"mode\":").append(serverMapUnloadModeLast)
            .append(",\"ready\":").append(serverMapUnloadReadyLast)
            .append(",\"maxCells\":").append(serverMapUnloadMaxCellsLast)
            .append(",\"slices\":").append(serverMapUnloadSlicesLast)
            .append(",\"attempts\":").append(serverMapUnloadAttempts)
            .append(",\"partial\":").append(serverMapUnloadPartialCells)
            .append("}");
        json.append(",\"load2Budget\":{\"maxCells\":").append(serverMapLoad2MaxCellsLast)
            .append(",\"ready\":").append(serverMapLoad2ReadyLast)
            .append(",\"flushed\":").append(serverMapLoad2FlushedLast)
            .append(",\"backlog\":").append(serverMapLoad2BacklogLast)
            .append(",\"attempts\":").append(serverMapLoad2Attempts)
            .append(",\"deferredCells\":").append(serverMapLoad2DeferredCells)
            .append("}");
        json.append(",\"serverMapPreQueues\":{")
            .append("\"load\":{\"last\":").append(serverMapPreLoadQueueLast).append(",\"max\":").append(serverMapPreLoadQueueMax.get()).append("}")
            .append(",\"loaded\":{\"last\":").append(serverMapPreLoadedQueueLast).append(",\"max\":").append(serverMapPreLoadedQueueMax.get()).append("}")
            .append(",\"recalc\":{\"last\":").append(serverMapPreRecalcQueueLast).append(",\"max\":").append(serverMapPreRecalcQueueMax.get()).append("}")
            .append(",\"recalcDone\":{\"last\":").append(serverMapPreRecalcDoneQueueLast).append(",\"max\":").append(serverMapPreRecalcDoneQueueMax.get()).append("}")
            .append(",\"save\":{\"last\":").append(serverMapPreSaveQueueLast).append(",\"max\":").append(serverMapPreSaveQueueMax.get()).append("}")
            .append("}");
        json.append(",\"serverMapPrePhases\":{");
        for (int i = 0; i < SERVER_MAP_PRE_KEYS.length; i++) {
            long calls = serverMapPreCalls[i].sum();
            if (i > 0) json.append(",");
            json.append("\"").append(SERVER_MAP_PRE_KEYS[i]).append("\":{\"calls\":").append(calls)
                .append(",\"units\":").append(serverMapPreUnits[i].sum())
                .append(",\"avgMs\":").append(avgMs(serverMapPreNanos[i].sum(), calls))
                .append(",\"maxMs\":").append(ms(serverMapPreMaxNanos[i].get()))
                .append("}");
        }
        json.append("}");
        json.append(",\"serverMapPostPhases\":{");
        for (int i = 0; i < SERVER_MAP_POST_KEYS.length; i++) {
            long calls = serverMapPostCalls[i].sum();
            if (i > 0) json.append(",");
            json.append("\"").append(SERVER_MAP_POST_KEYS[i]).append("\":{\"calls\":").append(calls)
                .append(",\"units\":").append(serverMapPostUnits[i].sum())
                .append(",\"avgMs\":").append(avgMs(serverMapPostNanos[i].sum(), calls))
                .append(",\"maxMs\":").append(ms(serverMapPostMaxNanos[i].get()))
                .append("}");
        }
        json.append("}");
        json.append(",\"unloadPhases\":{");
        for (int i = 0; i < SERVER_MAP_UNLOAD_PHASE_KEYS.length; i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(SERVER_MAP_UNLOAD_PHASE_KEYS[i]).append("\":{\"calls\":").append(serverMapUnloadPhaseCalls[i])
                .append(",\"units\":").append(serverMapUnloadPhaseUnits[i])
                .append(",\"avgMs\":").append(avgMs(serverMapUnloadPhaseNanos[i], serverMapUnloadPhaseCalls[i]))
                .append(",\"maxMs\":").append(ms(serverMapUnloadPhaseMaxNanos[i])).append("}");
        }
        json.append("}");
        json.append(",\"unloadDetails\":{");
        for (int i = 0; i < SERVER_MAP_UNLOAD_DETAIL_KEYS.length; i++) {
            long calls = serverMapUnloadDetailCalls[i].sum();
            if (i > 0) json.append(",");
            json.append("\"").append(SERVER_MAP_UNLOAD_DETAIL_KEYS[i]).append("\":{\"calls\":").append(calls)
                .append(",\"units\":").append(serverMapUnloadDetailUnits[i].sum())
                .append(",\"avgMs\":").append(avgMs(serverMapUnloadDetailNanos[i].sum(), calls))
                .append(",\"maxMs\":").append(ms(serverMapUnloadDetailMaxNanos[i].get())).append("}");
        }
        json.append("}");
        json.append(",\"state\":{\"players\":").append(playersLast)
            .append(",\"zombies\":").append(zombiesLast)
            .append(",\"connections\":").append(connectionsLast)
            .append("}");
        json.append(",\"queues\":{\"high\":").append(highQueueLast)
            .append(",\"player\":").append(playerQueueLast)
            .append(",\"normal\":").append(normalQueueLast)
            .append("}");
        long netHigh = netHighPackets.sum();
        long netPlayer = netPlayerPackets.sum();
        long netNormal = netNormalPackets.sum();
        json.append(",\"netLoop\":{\"high\":{\"packets\":").append(netHigh)
            .append(",\"avgMs\":").append(avgMs(netHighNanos.sum(), worldTicks))
            .append(",\"avgPacketMs\":").append(avgMs(netHighNanos.sum(), netHigh))
            .append(",\"maxMs\":").append(ms(netHighMaxNanos.get()))
            .append("},\"player\":{\"packets\":").append(netPlayer)
            .append(",\"avgMs\":").append(avgMs(netPlayerNanos.sum(), worldTicks))
            .append(",\"avgPacketMs\":").append(avgMs(netPlayerNanos.sum(), netPlayer))
            .append(",\"maxMs\":").append(ms(netPlayerMaxNanos.get()))
            .append("},\"normal\":{\"packets\":").append(netNormal)
            .append(",\"processed\":").append(netNormalProcessed.sum())
            .append(",\"dropped\":").append(netNormalDropped.sum())
            .append(",\"avgMs\":").append(avgMs(netNormalNanos.sum(), worldTicks))
            .append(",\"avgPacketMs\":").append(avgMs(netNormalNanos.sum(), netNormal))
            .append(",\"maxMs\":").append(ms(netNormalMaxNanos.get()))
            .append("}}");
        long losCalcsCount = losCalcs.sum();
        json.append(",\"los\":{\"slots\":").append(losSlotCount)
            .append(",\"busyMax\":").append(losSlotsBusyMax.get())
            .append(",\"calcs\":").append(losCalcsCount)
            .append(",\"skipped\":").append(losSkipped.sum())
            .append(",\"phased\":").append(losPhased.sum())
            .append(",\"forced\":").append(losForced.sum())
            .append(",\"starved\":").append(losStarved.sum())
            .append(",\"avgMs\":").append(avgMs(losNanos.sum(), losCalcsCount))
            .append(",\"maxMs\":").append(ms(losMaxNanos.get()))
            .append("}");
        long authGridBuilds = zombieAuthGridBuilds.sum();
        long authQueries = zombieAuthQueries.sum();
        long authUpdateCalls = zombieAuthUpdateCalls.sum();
        long authListCalls = zombieAuthListCalls.sum();
        long relayGridBuilds = zombieRelayGridBuilds.sum();
        long relayQueries = zombieRelayQueries.sum();
        long relayPackets = zombieRelayPackets.sum();
        long relayPostCalls = zombieRelayPostCalls.sum();
        long relayConnectionCalls = zombieRelayConnectionCalls.sum();
        long groupGridBuilds = zombieGroupGridBuilds.sum();
        long groupQueries = zombieGroupQueries.sum();
        long zombieUpdates = zombieServerUpdateCalls.sum();
        json.append(",\"zombieNet\":{\"auth\":{\"gridBuilds\":").append(authGridBuilds)
            .append(",\"avgCells\":").append(avg(zombieAuthGridCells.sum(), authGridBuilds))
            .append(",\"avgCandidates\":").append(avg(zombieAuthGridCandidates.sum(), authGridBuilds))
            .append(",\"avgCellWrites\":").append(avg(zombieAuthGridCellWrites.sum(), authGridBuilds))
            .append(",\"avgBuildMs\":").append(avgMs(zombieAuthGridNanos.sum(), authGridBuilds))
            .append(",\"maxBuildMs\":").append(ms(zombieAuthGridMaxNanos.get()))
            .append(",\"queries\":").append(authQueries)
            .append(",\"avgQueryCandidates\":").append(avg(zombieAuthQueryCandidates.sum(), authQueries))
            .append(",\"moves\":").append(zombieAuthMoves.sum())
            .append(",\"updateCalls\":").append(authUpdateCalls)
            .append(",\"avgUpdateZombies\":").append(avg(zombieAuthUpdateZombies.sum(), authUpdateCalls))
            .append(",\"avgUpdateMs\":").append(avgMs(zombieAuthUpdateNanos.sum(), authUpdateCalls))
            .append(",\"maxUpdateMs\":").append(ms(zombieAuthUpdateMaxNanos.get()))
            .append(",\"listCalls\":").append(authListCalls)
            .append(",\"avgListMs\":").append(avgMs(zombieAuthListNanos.sum(), authListCalls))
            .append(",\"maxListMs\":").append(ms(zombieAuthListMaxNanos.get()))
            .append("},\"relay\":{\"gridBuilds\":").append(relayGridBuilds)
            .append(",\"avgActive\":").append(avg(zombieRelayGridActive.sum(), relayGridBuilds))
            .append(",\"avgCells\":").append(avg(zombieRelayGridCells.sum(), relayGridBuilds))
            .append(",\"avgBuildMs\":").append(avgMs(zombieRelayGridNanos.sum(), relayGridBuilds))
            .append(",\"maxBuildMs\":").append(ms(zombieRelayGridMaxNanos.get()))
            .append(",\"queries\":").append(relayQueries)
            .append(",\"avgCellsVisited\":").append(avg(zombieRelayCellsVisited.sum(), relayQueries))
            .append(",\"avgCandidates\":").append(avg(zombieRelayCandidates.sum(), relayQueries))
            .append(",\"initialSent\":").append(zombieRelayInitialSent.sum())
            .append(",\"sent\":").append(zombieRelaySent.sum())
            .append(",\"packets\":").append(relayPackets)
            .append(",\"extraAllMarks\":").append(zombieRelayExtraAllMarks.sum())
            .append(",\"extraAllPackets\":").append(zombieRelayExtraAllPackets.sum())
            .append(",\"postCalls\":").append(relayPostCalls)
            .append(",\"avgPostMs\":").append(avgMs(zombieRelayPostNanos.sum(), relayPostCalls))
            .append(",\"maxPostMs\":").append(ms(zombieRelayPostMaxNanos.get()))
            .append(",\"connectionCalls\":").append(relayConnectionCalls)
            .append(",\"avgConnectionMs\":").append(avgMs(zombieRelayConnectionNanos.sum(), relayConnectionCalls))
            .append(",\"maxConnectionMs\":").append(ms(zombieRelayConnectionMaxNanos.get()))
            .append(",\"avgGetDataMs\":").append(avgMs(zombieRelayGetDataNanos.sum(), relayConnectionCalls))
            .append(",\"maxGetDataMs\":").append(ms(zombieRelayGetDataMaxNanos.get()))
            .append(",\"avgSendMs\":").append(avgMs(zombieRelaySendNanos.sum(), relayConnectionCalls))
            .append(",\"maxSendMs\":").append(ms(zombieRelaySendMaxNanos.get()))
            .append("}}");
        json.append(",\"zombieGroups\":{\"gridBuilds\":").append(groupGridBuilds)
            .append(",\"avgGroups\":").append(avg(zombieGroupGridGroups.sum(), groupGridBuilds))
            .append(",\"avgCells\":").append(avg(zombieGroupGridCells.sum(), groupGridBuilds))
            .append(",\"avgBuildMs\":").append(avgMs(zombieGroupGridNanos.sum(), groupGridBuilds))
            .append(",\"maxBuildMs\":").append(ms(zombieGroupGridMaxNanos.get()))
            .append(",\"queries\":").append(groupQueries)
            .append(",\"avgCandidates\":").append(avg(zombieGroupCandidates.sum(), groupQueries))
            .append(",\"emptyRemoved\":").append(zombieGroupEmptyRemoved.sum())
            .append("}");
        json.append(",\"zombieUpdate\":{\"serverCalls\":").append(zombieUpdates)
            .append(",\"owned\":").append(zombieServerUpdateOwned.sum())
            .append(",\"target\":").append(zombieServerUpdateTarget.sum())
            .append(",\"remote\":").append(zombieServerUpdateRemote.sum())
            .append(",\"avgMs\":").append(avgMs(zombieServerUpdateNanos.sum(), zombieUpdates))
            .append(",\"maxMs\":").append(ms(zombieServerUpdateMaxNanos.get()))
            .append("}");
        long popUpdates = zombiePopUpdates.sum();
        json.append(",\"zombiePop\":{\"updates\":").append(popUpdates)
            .append(",\"nativeRequested\":").append(zombiePopNativeRequested.sum())
            .append(",\"batches\":").append(zombiePopBatches.sum())
            .append(",\"recordsRead\":").append(zombiePopRecordsRead.sum())
            .append(",\"skippedNewIndoor\":").append(zombiePopSkippedNewIndoor.sum())
            .append(",\"edgeForcedStanding\":").append(zombiePopEdgeForcedStanding.sum())
            .append(",\"standing\":").append(zombiePopStanding.sum())
            .append(",\"moving\":").append(zombiePopMoving.sum())
            .append(",\"avgMs\":").append(avgMs(zombiePopNanos.sum(), popUpdates))
            .append(",\"maxMs\":").append(ms(zombiePopMaxNanos.get()))
            .append("}");
        appendDynamicTimingMap(json, "luaEvents", luaEvents, LUA_TELEMETRY_TOP_N);
        appendDynamicTimingMap(json, "luaCallbacks", luaCallbacks, LUA_TELEMETRY_TOP_N);
        appendDynamicTimingMap(json, "luaDirect", luaDirect, LUA_TELEMETRY_TOP_N);
        appendDynamicTimingMap(json, "mainThreadTasks", mainThreadTasks, LUA_TELEMETRY_TOP_N);
        appendDynamicTimingMap(json, "netHighPackets", netHighPacketsByType, LUA_TELEMETRY_TOP_N);
        appendDynamicTimingMap(json, "netHighDetails", netHighDetails, LUA_TELEMETRY_TOP_N);
        json.append("}");
        return json.toString();
    }

    private static void resetWorldCounters() {
        worldTicks = 0L;
        worldNanos = 0L;
        worldMaxNanos = 0L;
        for (int i = 0; i < TICK_SECTION_KEYS.length; i++) {
            tickSectionCalls[i].reset();
            tickSectionNanos[i].reset();
            tickSectionMaxNanos[i].set(0L);
        }
        for (int i = 0; i < SERVER_MAP_PRE_KEYS.length; i++) {
            serverMapPreCalls[i].reset();
            serverMapPreUnits[i].reset();
            serverMapPreNanos[i].reset();
            serverMapPreMaxNanos[i].set(0L);
        }
        for (int i = 0; i < SERVER_MAP_POST_KEYS.length; i++) {
            serverMapPostCalls[i].reset();
            serverMapPostUnits[i].reset();
            serverMapPostNanos[i].reset();
            serverMapPostMaxNanos[i].set(0L);
        }
        serverMapPreLoadQueueMax.set(0L);
        serverMapPreLoadedQueueMax.set(0L);
        serverMapPreRecalcQueueMax.set(0L);
        serverMapPreRecalcDoneQueueMax.set(0L);
        serverMapPreSaveQueueMax.set(0L);
        serverMapUnloadQueued = 0L;
        serverMapUnloadRevalidated = 0L;
        serverMapUnloadCells = 0L;
        serverMapUnloadNanos = 0L;
        serverMapUnloadMaxNanos = 0L;
        serverMapUnloadAttempts = 0L;
        serverMapUnloadPartialCells = 0L;
        serverMapLoad2Attempts = 0L;
        serverMapLoad2DeferredCells = 0L;
        for (int i = 0; i < SERVER_MAP_UNLOAD_PHASE_KEYS.length; i++) {
            serverMapUnloadPhaseCalls[i] = 0L;
            serverMapUnloadPhaseUnits[i] = 0L;
            serverMapUnloadPhaseNanos[i] = 0L;
            serverMapUnloadPhaseMaxNanos[i] = 0L;
        }
        for (int i = 0; i < SERVER_MAP_UNLOAD_DETAIL_KEYS.length; i++) {
            serverMapUnloadDetailCalls[i].reset();
            serverMapUnloadDetailUnits[i].reset();
            serverMapUnloadDetailNanos[i].reset();
            serverMapUnloadDetailMaxNanos[i].set(0L);
        }
        // state/queue "last" values are intentionally left as-is: they get
        // overwritten by the next sampler snapshot regardless, and showing the
        // last known value between snapshots is more useful than resetting to 0.
        netHighPackets.reset();
        netHighNanos.reset();
        netHighMaxNanos.set(0L);
        netPlayerPackets.reset();
        netPlayerNanos.reset();
        netPlayerMaxNanos.set(0L);
        netNormalPackets.reset();
        netNormalProcessed.reset();
        netNormalDropped.reset();
        netNormalNanos.reset();
        netNormalMaxNanos.set(0L);
        losCalcs.reset();
        losSkipped.reset();
        losPhased.reset();
        losForced.reset();
        losStarved.reset();
        losNanos.reset();
        losMaxNanos.set(0L);
        // Reset the "max busy" watermark to the current busy count rather than 0 - LOS calc
        // tasks dispatched just before this reset may still be in flight, and reporting 0
        // busy while N are actually running would be a false "idle" signal for the next
        // interval's peak.
        losSlotsBusyMax.set(losSlotsBusy.get());
        zombieAuthGridBuilds.reset();
        zombieAuthGridCells.reset();
        zombieAuthGridCandidates.reset();
        zombieAuthGridCellWrites.reset();
        zombieAuthGridNanos.reset();
        zombieAuthGridMaxNanos.set(0L);
        zombieAuthQueries.reset();
        zombieAuthQueryCandidates.reset();
        zombieAuthMoves.reset();
        zombieAuthUpdateCalls.reset();
        zombieAuthUpdateZombies.reset();
        zombieAuthUpdateNanos.reset();
        zombieAuthUpdateMaxNanos.set(0L);
        zombieAuthListCalls.reset();
        zombieAuthListNanos.reset();
        zombieAuthListMaxNanos.set(0L);
        zombieRelayGridBuilds.reset();
        zombieRelayGridActive.reset();
        zombieRelayGridCells.reset();
        zombieRelayGridNanos.reset();
        zombieRelayGridMaxNanos.set(0L);
        zombieRelayQueries.reset();
        zombieRelayCellsVisited.reset();
        zombieRelayCandidates.reset();
        zombieRelayInitialSent.reset();
        zombieRelaySent.reset();
        zombieRelayPackets.reset();
        zombieRelayExtraAllMarks.reset();
        zombieRelayExtraAllPackets.reset();
        zombieRelayPostCalls.reset();
        zombieRelayPostNanos.reset();
        zombieRelayPostMaxNanos.set(0L);
        zombieRelayConnectionCalls.reset();
        zombieRelayConnectionNanos.reset();
        zombieRelayConnectionMaxNanos.set(0L);
        zombieRelayGetDataNanos.reset();
        zombieRelayGetDataMaxNanos.set(0L);
        zombieRelaySendNanos.reset();
        zombieRelaySendMaxNanos.set(0L);
        zombieGroupGridBuilds.reset();
        zombieGroupGridGroups.reset();
        zombieGroupGridCells.reset();
        zombieGroupGridNanos.reset();
        zombieGroupGridMaxNanos.set(0L);
        zombieGroupQueries.reset();
        zombieGroupCandidates.reset();
        zombieGroupEmptyRemoved.reset();
        zombieServerUpdateCalls.reset();
        zombieServerUpdateOwned.reset();
        zombieServerUpdateTarget.reset();
        zombieServerUpdateRemote.reset();
        zombieServerUpdateNanos.reset();
        zombieServerUpdateMaxNanos.set(0L);
        zombiePopUpdates.reset();
        zombiePopNativeRequested.reset();
        zombiePopBatches.reset();
        zombiePopRecordsRead.reset();
        zombiePopSkippedNewIndoor.reset();
        zombiePopEdgeForcedStanding.reset();
        zombiePopStanding.reset();
        zombiePopMoving.reset();
        zombiePopNanos.reset();
        zombiePopMaxNanos.set(0L);
        luaEvents.clear();
        luaCallbacks.clear();
        luaDirect.clear();
        mainThreadTasks.clear();
        netHighPacketsByType.clear();
        netHighDetails.clear();
    }

    private static void offerNdjson(String payload) {
        if (!NDJSON_ENABLED || payload == null) {
            return;
        }

        startNdjsonWriter();
        if (!ndjsonQueue.offer(payload)) {
            ndjsonDropped.increment();
        }
    }

    private static void startNdjsonWriter() {
        if (!ndjsonWriterStarted.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(ApocBRServerTelemetry::runNdjsonWriter, "ApocBR-Telemetry-NDJSON");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runNdjsonWriter() {
        Path path = Paths.get(NDJSON_PATH);
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            DebugType.General.printException(e, "ApocBRServerTelemetry: failed to create NDJSON telemetry directory", LogSeverity.Warning);
        }

        OpenOption[] options = new OpenOption[] {
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
        };

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, options)) {
            while (true) {
                String payload = ndjsonQueue.take();
                writer.write(payload);
                writer.newLine();
                writer.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            DebugType.General.printException(t, "ApocBRServerTelemetry: NDJSON writer stopped", LogSeverity.Warning);
        }
    }

    private static double avgMs(long nanos, long count) {
        return count <= 0L ? 0.0 : round2((double) nanos / (double) count / 1000000.0);
    }

    private static double ms(long nanos) {
        return round2((double) nanos / 1000000.0);
    }

    private static double avg(long value, long count) {
        return count <= 0L ? 0.0 : round2((double)value / (double)count);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void appendDynamicTimingMap(StringBuilder json, String name, ConcurrentHashMap<String, DynamicTiming> map, int limit) {
        json.append(",\"").append(name).append("\":{");
        ArrayList<Map.Entry<String, DynamicTiming>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, DynamicTiming> entry) -> entry.getValue().nanos.sum()).reversed());
        int emitted = 0;
        for (Map.Entry<String, DynamicTiming> entry : entries) {
            if (emitted >= limit) break;
            DynamicTiming timing = entry.getValue();
            long calls = timing.calls.sum();
            long nanos = timing.nanos.sum();
            if (calls <= 0L && nanos <= 0L) continue;
            if (emitted > 0) json.append(",");
            appendJsonString(json, entry.getKey());
            json.append(":{\"calls\":").append(calls)
                .append(",\"units\":").append(timing.units.sum())
                .append(",\"avgMs\":").append(avgMs(nanos, calls))
                .append(",\"totalMs\":").append(ms(nanos))
                .append(",\"maxMs\":").append(ms(timing.maxNanos.get()))
                .append("}");
            emitted++;
        }
        json.append("}");
    }

    private static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 32) {
                json.append("\\u");
                String hex = Integer.toHexString(c);
                for (int n = hex.length(); n < 4; n++) {
                    json.append('0');
                }
                json.append(hex);
            } else {
                json.append(c);
            }
        }
        json.append('"');
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

    private static String getString(String key, String def) {
        String value = System.getProperty(key);
        return value == null || value.trim().isEmpty() ? def : value.trim();
    }

    private static void logProperty(String key, String effectiveValue, String defaultValue) {
        String rawValue = System.getProperty(key);
        String source = rawValue == null ? "default" : "system";
        DebugLog.log(
            "[ApocBR]   "
                + key
                + "="
                + effectiveValue
                + " ("
                + source
                + ", default="
                + defaultValue
                + ")"
        );
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static LongAdder[] newLongAdders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int i = 0; i < count; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    private static AtomicLong[] newAtomicLongs(int count) {
        AtomicLong[] atomics = new AtomicLong[count];
        for (int i = 0; i < count; i++) {
            atomics[i] = new AtomicLong();
        }
        return atomics;
    }

    private static final class DynamicTiming {
        private final LongAdder calls = new LongAdder();
        private final LongAdder units = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
    }
}
