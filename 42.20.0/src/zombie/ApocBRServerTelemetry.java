package zombie;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import zombie.debug.DebugLog;

/**
 * Minimal server-side telemetry for ApocBR patches on build 42.20.0.
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
 * co-op cap it replaced).
 */
public final class ApocBRServerTelemetry {
    private static final boolean ENABLED = getBoolean("apocbr.telemetry.enabled", true);
    private static final long INTERVAL_MS = clamp(getLong("apocbr.telemetry.intervalMs", 30000L), 5000L, 300000L);

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
    private static final String[] SERVER_MAP_UNLOAD_PHASE_KEYS = new String[] {
        "chunkGlobal", "squareTeardown", "vehicleSave", "saveEnqueue"
    };
    private static final long[] serverMapUnloadPhaseCalls = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseUnits = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseNanos = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];
    private static final long[] serverMapUnloadPhaseMaxNanos = new long[SERVER_MAP_UNLOAD_PHASE_KEYS.length];

    private static int playersLast;
    private static int zombiesLast;
    private static int connectionsLast;
    private static int highQueueLast;
    private static int playerQueueLast;
    private static int normalQueueLast;

    private static volatile int losSlotCount;
    private static final AtomicInteger losSlotsBusy = new AtomicInteger();
    private static final AtomicInteger losSlotsBusyMax = new AtomicInteger();
    private static final LongAdder losCalcs = new LongAdder();
    private static final LongAdder losSkipped = new LongAdder();
    private static final LongAdder losStarved = new LongAdder();
    private static final LongAdder losNanos = new LongAdder();
    private static final AtomicLong losMaxNanos = new AtomicLong();

    private ApocBRServerTelemetry() {
    }

    public static synchronized void recordWorldTick(long nanos) {
        if (!ENABLED) return;
        worldTicks++;
        worldNanos += nanos;
        worldMaxNanos = Math.max(worldMaxNanos, nanos);
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
        json.append(",\"unload\":{\"pending\":").append(serverMapUnloadPendingLast)
            .append(",\"queued\":").append(serverMapUnloadQueued)
            .append(",\"revalidated\":").append(serverMapUnloadRevalidated)
            .append(",\"cells\":").append(serverMapUnloadCells)
            .append(",\"avgMs\":").append(avgMs(serverMapUnloadNanos, serverMapUnloadCells))
            .append(",\"maxMs\":").append(ms(serverMapUnloadMaxNanos))
            .append(",\"oldestAgeMs\":").append(serverMapUnloadOldestAgeMsLast)
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
        json.append(",\"state\":{\"players\":").append(playersLast)
            .append(",\"zombies\":").append(zombiesLast)
            .append(",\"connections\":").append(connectionsLast)
            .append("}");
        json.append(",\"queues\":{\"high\":").append(highQueueLast)
            .append(",\"player\":").append(playerQueueLast)
            .append(",\"normal\":").append(normalQueueLast)
            .append("}");
        long losCalcsCount = losCalcs.sum();
        json.append(",\"los\":{\"slots\":").append(losSlotCount)
            .append(",\"busyMax\":").append(losSlotsBusyMax.get())
            .append(",\"calcs\":").append(losCalcsCount)
            .append(",\"skipped\":").append(losSkipped.sum())
            .append(",\"starved\":").append(losStarved.sum())
            .append(",\"avgMs\":").append(avgMs(losNanos.sum(), losCalcsCount))
            .append(",\"maxMs\":").append(ms(losMaxNanos.get()))
            .append("}");
        json.append("}");
        return json.toString();
    }

    private static void resetWorldCounters() {
        worldTicks = 0L;
        worldNanos = 0L;
        worldMaxNanos = 0L;
        serverMapUnloadQueued = 0L;
        serverMapUnloadRevalidated = 0L;
        serverMapUnloadCells = 0L;
        serverMapUnloadNanos = 0L;
        serverMapUnloadMaxNanos = 0L;
        for (int i = 0; i < SERVER_MAP_UNLOAD_PHASE_KEYS.length; i++) {
            serverMapUnloadPhaseCalls[i] = 0L;
            serverMapUnloadPhaseUnits[i] = 0L;
            serverMapUnloadPhaseNanos[i] = 0L;
            serverMapUnloadPhaseMaxNanos[i] = 0L;
        }
        // state/queue "last" values are intentionally left as-is: they get
        // overwritten by the next sampler snapshot regardless, and showing the
        // last known value between snapshots is more useful than resetting to 0.
        losCalcs.reset();
        losSkipped.reset();
        losStarved.reset();
        losNanos.reset();
        losMaxNanos.set(0L);
        // Reset the "max busy" watermark to the current busy count rather than 0 - LOS calc
        // tasks dispatched just before this reset may still be in flight, and reporting 0
        // busy while N are actually running would be a false "idle" signal for the next
        // interval's peak.
        losSlotsBusyMax.set(losSlotsBusy.get());
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
