package zombie;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
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
    private static final boolean ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);
    private static final boolean NDJSON_ENABLED = getBoolean("apocbr.telemetry.ndjson.enabled", true);
    private static final String NDJSON_PATH = getString("apocbr.telemetry.ndjson.path", "apocbr-telemetry.ndjson");
    private static final int NDJSON_QUEUE_CAPACITY = (int)clamp(getLong("apocbr.telemetry.ndjson.queue", 64L), 1L, 4096L);
    private static final ArrayBlockingQueue<String> ndjsonQueue = new ArrayBlockingQueue<>(NDJSON_QUEUE_CAPACITY);
    private static final AtomicBoolean ndjsonWriterStarted = new AtomicBoolean(false);
    private static final AtomicLong ndjsonSeq = new AtomicLong();
    private static final LongAdder ndjsonDropped = new LongAdder();

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
        "reuseGridsquares"
    };
    private static final LongAdder[] serverMapUnloadDetailCalls = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final LongAdder[] serverMapUnloadDetailUnits = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final LongAdder[] serverMapUnloadDetailNanos = newLongAdders(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);
    private static final AtomicLong[] serverMapUnloadDetailMaxNanos = newAtomicLongs(SERVER_MAP_UNLOAD_DETAIL_KEYS.length);

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

    private static final String[] TICK_SECTION_KEYS = new String[] {
        "netHigh", "netPlayer", "netNormal", "throttleSleep", "removeRequests", "rcon",
        "mapCollision", "gameState", "vehicleManager", "objectIdManager", "playersRelevant", "importantAreas",
        "connectionRelevant", "objectCleanup", "connectionTimeouts", "serverMapPre", "serverMapPost",
        "serverMapCellUpdate", "serverMapZombiePost", "serverMapUpdateSaved", "serverGui", "consoleCommands",
        "statsPublic", "connectionMaintenance", "worldMapPositions", "coop", "loginQueue", "zipBackup",
        "steamLoop", "trading", "war", "safehouse", "networkPlayer", "asyncTransactions", "worldMapVisited"
    };
    private static final LongAdder[] tickSectionCalls = newLongAdders(TICK_SECTION_KEYS.length);
    private static final LongAdder[] tickSectionNanos = newLongAdders(TICK_SECTION_KEYS.length);
    private static final AtomicLong[] tickSectionMaxNanos = newAtomicLongs(TICK_SECTION_KEYS.length);

    private ApocBRServerTelemetry() {
    }

    public static synchronized void recordWorldTick(long nanos) {
        if (!ENABLED) return;
        worldTicks++;
        worldNanos += nanos;
        worldMaxNanos = Math.max(worldMaxNanos, nanos);
    }

    public static void recordTickSection(String section, long nanos) {
        if (!ENABLED || nanos < 0L) return;
        for (int i = 0; i < TICK_SECTION_KEYS.length; i++) {
            if (TICK_SECTION_KEYS[i].equals(section)) {
                tickSectionCalls[i].increment();
                tickSectionNanos[i].add(nanos);
                tickSectionMaxNanos[i].accumulateAndGet(nanos, Math::max);
                return;
            }
        }
    }

    /**
     * Real per-tick accounting for the deferred/time-sliced cell unload
     * mechanism in the patched {@code zombie.network.ServerMap}. Called once
     * per {@code postupdate()} tick from {@code processDeferredUnloads()}.
     */
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

    public static synchronized void recordServerMapDeferredUnloadBudget(
        int mode, int ready, int maxCells, int slicesPerTick, int attempts, int partialCells
    ) {
        if (!ENABLED) return;
        serverMapUnloadModeLast = mode;
        serverMapUnloadReadyLast = ready;
        serverMapUnloadMaxCellsLast = maxCells;
        serverMapUnloadSlicesLast = slicesPerTick;
        serverMapUnloadAttempts += attempts;
        serverMapUnloadPartialCells += partialCells;
    }

    /**
     * Per-phase breakdown of unload cost (chunkGlobal = collision/pathfind
     * removal done once per chunk; squareTeardown = the time-sliced per-square
     * loop; vehicleSave/saveEnqueue = post-teardown bookkeeping). Lets us see
     * which phase dominates instead of only the aggregate unload cost.
     */
    public static synchronized void recordServerMapUnloadPhase(String phase, int units, long nanos) {
        if (!ENABLED) return;
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
        if (!ENABLED) return;
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
        if (!ENABLED) return;
        netHighPackets.add(packets);
        netHighNanos.add(nanos);
        netHighMaxNanos.accumulateAndGet(nanos, Math::max);
        recordTickSection("netHigh", nanos);
    }

    public static void recordMainLoopNetPlayer(int packets, long nanos) {
        if (!ENABLED) return;
        netPlayerPackets.add(packets);
        netPlayerNanos.add(nanos);
        netPlayerMaxNanos.accumulateAndGet(nanos, Math::max);
        recordTickSection("netPlayer", nanos);
    }

    public static void recordMainLoopNetNormal(int packets, int processed, int dropped, long nanos) {
        if (!ENABLED) return;
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
        if (!ENABLED) return;
        int busy = losSlotsBusy.incrementAndGet();
        losSlotsBusyMax.accumulateAndGet(busy, Math::max);
    }

    /**
     * Called from the LOS dispatcher thread when a WaitingInLOS player found no free slot on
     * this pass (the pool is fully saturated). Sustained non-zero values mean LOS_SLOT_COUNT
     * is the current bottleneck.
     */
    public static void recordServerLosStarved() {
        if (!ENABLED) return;
        losStarved.increment();
    }

    public static void recordServerLosPhased() {
        if (!ENABLED) return;
        losPhased.increment();
    }

    public static void recordServerLosForced() {
        if (!ENABLED) return;
        losForced.increment();
    }

    /**
     * Called from a PZForkJoinPool worker thread once a single player's calcLOS() call
     * finishes (in ServerLOS.dispatch()'s finally block). skipped mirrors calcLOS()'s own
     * fast path (player hasn't moved grid cell since last calc) - nanos is 0 in that case
     * since no square work was done.
     */
    public static void recordServerLosCalc(boolean skipped, long nanos) {
        if (!ENABLED) return;
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
        if (!ENABLED) return;
        zombieAuthGridBuilds.increment();
        zombieAuthGridCells.add(cells);
        zombieAuthGridCandidates.add(candidates);
        zombieAuthGridCellWrites.add(cellWrites);
        zombieAuthGridNanos.add(nanos);
        zombieAuthGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieAuthQuery(int candidates) {
        if (!ENABLED) return;
        zombieAuthQueries.increment();
        zombieAuthQueryCandidates.add(candidates);
    }

    public static void recordZombieAuthMove() {
        if (!ENABLED) return;
        zombieAuthMoves.increment();
    }

    public static void recordZombieAuthUpdate(int zombies, long nanos) {
        if (!ENABLED) return;
        zombieAuthUpdateCalls.increment();
        zombieAuthUpdateZombies.add(zombies);
        zombieAuthUpdateNanos.add(nanos);
        zombieAuthUpdateMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieAuthList(long nanos) {
        if (!ENABLED) return;
        zombieAuthListCalls.increment();
        zombieAuthListNanos.add(nanos);
        zombieAuthListMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayGrid(int activeZombies, int cells, long nanos) {
        if (!ENABLED) return;
        zombieRelayGridBuilds.increment();
        zombieRelayGridActive.add(activeZombies);
        zombieRelayGridCells.add(cells);
        zombieRelayGridNanos.add(nanos);
        zombieRelayGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayQuery(int cellsVisited, int candidates, int sent) {
        if (!ENABLED) return;
        zombieRelayQueries.increment();
        zombieRelayCellsVisited.add(cellsVisited);
        zombieRelayCandidates.add(candidates);
        zombieRelaySent.add(sent);
    }

    public static void recordZombieRelayInitial(int sent) {
        if (!ENABLED) return;
        zombieRelayInitialSent.add(sent);
    }

    public static void recordZombieRelayPacket(boolean extraAll) {
        if (!ENABLED) return;
        zombieRelayPackets.increment();
        if (extraAll) {
            zombieRelayExtraAllPackets.increment();
        }
    }

    public static void recordZombieRelayExtraAllMark() {
        if (!ENABLED) return;
        zombieRelayExtraAllMarks.increment();
    }

    public static void recordZombieRelayPost(long nanos) {
        if (!ENABLED) return;
        zombieRelayPostCalls.increment();
        zombieRelayPostNanos.add(nanos);
        zombieRelayPostMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayConnection(long nanos) {
        if (!ENABLED) return;
        zombieRelayConnectionCalls.increment();
        zombieRelayConnectionNanos.add(nanos);
        zombieRelayConnectionMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelayGetData(long nanos) {
        if (!ENABLED) return;
        zombieRelayGetDataNanos.add(nanos);
        zombieRelayGetDataMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieRelaySend(long nanos) {
        if (!ENABLED) return;
        zombieRelaySendNanos.add(nanos);
        zombieRelaySendMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieGroupGrid(int groups, int cells, long nanos) {
        if (!ENABLED) return;
        zombieGroupGridBuilds.increment();
        zombieGroupGridGroups.add(groups);
        zombieGroupGridCells.add(cells);
        zombieGroupGridNanos.add(nanos);
        zombieGroupGridMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    public static void recordZombieGroupQuery(int candidates, boolean removedEmptyGroups) {
        if (!ENABLED) return;
        zombieGroupQueries.increment();
        zombieGroupCandidates.add(candidates);
        if (removedEmptyGroups) {
            zombieGroupEmptyRemoved.increment();
        }
    }

    public static void recordZombieServerUpdate(long nanos, boolean owned, boolean hasTarget, boolean remote) {
        if (!ENABLED) return;
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
        if (!ENABLED) return;
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
        String payload = buildJsonPayload(now);
        DebugLog.log("[ApocBRTelemetry]" + payload);
        offerNdjson(payload);
        resetWorldCounters();
        nextLogMs = now + INTERVAL_MS;
    }

    private static String buildJsonPayload(long now) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\"schemaVersion\":1");
        json.append(",\"seq\":").append(ndjsonSeq.incrementAndGet());
        json.append(",\"ts\":").append(now);
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
        serverMapUnloadQueued = 0L;
        serverMapUnloadRevalidated = 0L;
        serverMapUnloadCells = 0L;
        serverMapUnloadNanos = 0L;
        serverMapUnloadMaxNanos = 0L;
        serverMapUnloadAttempts = 0L;
        serverMapUnloadPartialCells = 0L;
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
}
