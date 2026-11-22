package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** Fixed set of optimization measurements, emitted in the existing schema-3 snapshot. */
public final class OptimizationTelemetry {
    private OptimizationTelemetry() {
    }

    public enum Counter {
        CULLING_ZERO_QUOTA("zombies.culling.zeroQuotaConnections"),
        CULLING_FULFILLED_QUOTA("zombies.culling.fulfilledQuotaConnections"),
        LUA_MEMBERSHIP_SCAN_SKIPPED("lua.dispatch.membershipScansSkipped"),
        NEAR_VEHICLE_QUERY_SKIPPED("players.nearVehicle.queriesSkipped"),
        PACKET_PATH_SQRT_SKIPPED("zombies.packet.pathSquareRootsSkipped"),
        CLOTHING_REBUILT("players.thermal.clothingRebuilt"),
        ENTITY_REMOVAL_INDEXED("entities.removal.indexed"),
        ENTITY_CATCHUP_TICKS("entities.simulationCatchupTicks"),
        ENTITY_META_NOT_DUE("entities.metaNotDue"),
        ENTITY_BUCKET_SKIPPED("entities.simulationSkippedByIndex"),
        ENTITY_USING_PLAYER_CANDIDATES("entities.usingPlayer.candidatesVisited"),
        LOS_QUEUED("players.los.jobsQueued"),
        LOS_QUEUE_REJECTED("players.los.jobsRejected"),
        LOS_STALE("players.los.staleResults"),
        LOS_ZOMBIE_CANDIDATES("players.los.zombieCandidates"),
        ZOMBIE_SEND_BACKPRESSURE("zombies.async.backpressure"),
        ZOMBIE_SEND_FAILED("zombies.async.failed"),
        ZOMBIE_SEND_SUBMITTED("zombieJobs.submitted"),
        ZOMBIE_SEND_BYTES("zombies.async.snapshotBytes");

        private final String key;
        private final LongAdder value = new LongAdder();

        Counter(String key) {
            this.key = key;
        }
    }

    public enum Phase {
        NEAR_VEHICLE("simulation", "players", "nearVehicle"),
        PACKET_PREPARATION("zombies", "packet", "prepare"),
        THERMAL_CLOTHING("simulation", "players", "thermalClothing"),
        LOS_WORK("simulation", "players", "losWork"),
        LOS_COMPLETION("simulation", "players", "losCompletion"),
        LOS_SUSPEND("map", "losSuspendWait"),
        ZOMBIE_SEND_CAPTURE("network", "zombies", "snapshot"),
        ZOMBIE_SEND_QUEUE("network", "zombies", "async", "queueDelay"),
        ZOMBIE_SEND_TRANSPORT("network", "zombies", "async", "transport");

        private final List<String> path;
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAccumulator maxNanos = new LongAccumulator(Long::max, 0L);

        Phase(String... path) {
            this.path = List.of(path);
        }

        public void recordSince(long started) {
            long nanos = System.nanoTime() - started;
            totalNanos.add(nanos);
            maxNanos.accumulate(nanos);
            calls.increment();
        }
    }

    public static void count(Counter counter) {
        counter.value.increment();
    }

    public static void count(Counter counter, int amount) {
        counter.value.add(amount);
    }

    static Snapshot snapshotAndReset() {
        Map<String, Long> counters = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) {
            long amount = counter.value.sumThenReset();
            if (amount != 0) {
                counters.put(counter.key, amount);
            }
        }

        List<PhaseSample> phases = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            long calls = phase.calls.sumThenReset();
            long totalNanos = phase.totalNanos.sumThenReset();
            long maxNanos = phase.maxNanos.getThenReset();
            if (calls != 0) {
                phases.add(new PhaseSample(phase.path, calls, totalNanos, maxNanos));
            }
        }
        return new Snapshot(counters, phases);
    }

    record Snapshot(Map<String, Long> counters, List<PhaseSample> phases) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());
    }

    record PhaseSample(List<String> path, long calls, long totalNanos, long maxNanos) {
    }
}
