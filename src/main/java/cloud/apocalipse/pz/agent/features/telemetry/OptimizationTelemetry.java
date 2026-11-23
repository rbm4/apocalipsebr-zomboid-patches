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
        VEHICLE_FULL_CADENCE("vehicles.scheduler.fullChecks"),
        VEHICLE_PARKED_CADENCE("vehicles.scheduler.sixteenthChecks"),
        CELL_RELEVANCE_SCAN_SKIPPED("map.cell.relevanceScansSkipped"),
        BUCKET_REMOVAL_USING_PLAYER_INDEX("entities.removal.usingPlayerBucketIndexed"),
        ENTITY_BUCKET_REUSE_POSITION("entities.bucketRemoval.indexed"),
        ENTITY_BUCKET_POSITION_BUILD("entities.bucketRemoval.indexBuilds"),
        CHUNK_CONTAINER_CHECK_SKIPPED("map.chunk.containerChecksSkipped"),
        CHUNK_INTERIOR_UNLINK_SKIPPED("map.chunk.interiorUnlinksSkipped"),
        ENTITY_REMOVAL_INDEXED("entities.removal.indexed"),
        NPC_FURNITURE_OBJECT_INVALIDATED("npc.furniture.bindingsInvalidatedByObject"),
        NPC_FURNITURE_CELL_INVALIDATED("npc.furniture.bindingsInvalidatedByCell"),
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
        INCOMING("network", "incoming"),
        WORKSHOP_CHECK("workshop", "check"),
        MAP_PRE("map", "preupdate"),
        MAP_POST("map", "postupdate"),
        MAP_SAVE("map", "saveAll"),
        MAP_CELL_LOAD("map", "cell", "integrate"),
        MAP_CELL_UNLOAD("map", "cell", "unload"),
        MAP_CELL_UPDATE("map", "cell", "update"),
        MAP_CELL_VEHICLES("map", "cell", "loadVehicles"),
        RCON("rcon", "update"),
        COLLISION_STATE("collision", "updateGameState"),
        SIMULATION("simulation", "update"),
        VEHICLES("vehicles", "update"),
        DOWNLOADS("players", "relevanceAndDownloads", "downloadUpdate"),
        BACKUP("backup", "onPeriod"),
        TRADING("world", "socialSystems", "trading"),
        WAR("world", "socialSystems", "war"),
        SAFEHOUSE("world", "socialSystems", "safehouse"),
        NETWORK_PLAYERS("network", "players", "update"),
        WORLD_MAP("world", "asyncAndMap", "map"),
        ASYNC_FILES("world", "asyncAndMap", "files"),
        STEAM_PUMP("workshop", "steamPump"),
        WORLD("simulation", "world"),
        CELL("simulation", "cell"),
        MOVING("simulation", "movingObjects", "update"),
        MOVING_SETUP("simulation", "movingObjects", "startFrame"),
        MOVING_UPDATE_CPU("simulation", "movingObjects", "cpu", "update"),
        MOVING_POSTUPDATE_CPU("simulation", "movingObjects", "cpu", "postupdate"),
        MOVING_PREUPDATE_PLAYER("simulation", "movingObjects", "callbacks", "preupdate", "player"),
        MOVING_PREUPDATE_VEHICLE("simulation", "movingObjects", "callbacks", "preupdate", "vehicle"),
        MOVING_PREUPDATE_ANIMAL("simulation", "movingObjects", "callbacks", "preupdate", "animal"),
        MOVING_PREUPDATE_ZOMBIE("simulation", "movingObjects", "callbacks", "preupdate", "zombie"),
        MOVING_PREUPDATE_OTHER("simulation", "movingObjects", "callbacks", "preupdate", "other"),
        MOVING_FRAME_STEP_PLAYER("simulation", "movingObjects", "callbacks", "frameStep", "player"),
        MOVING_FRAME_STEP_VEHICLE("simulation", "movingObjects", "callbacks", "frameStep", "vehicle"),
        MOVING_FRAME_STEP_ANIMAL("simulation", "movingObjects", "callbacks", "frameStep", "animal"),
        MOVING_FRAME_STEP_ZOMBIE("simulation", "movingObjects", "callbacks", "frameStep", "zombie"),
        MOVING_FRAME_STEP_OTHER("simulation", "movingObjects", "callbacks", "frameStep", "other"),
        MOVING_UPDATE_PLAYER("simulation", "movingObjects", "callbacks", "update", "player"),
        MOVING_UPDATE_VEHICLE("simulation", "movingObjects", "callbacks", "update", "vehicle"),
        MOVING_UPDATE_ANIMAL("simulation", "movingObjects", "callbacks", "update", "animal"),
        MOVING_UPDATE_ZOMBIE("simulation", "movingObjects", "callbacks", "update", "zombie"),
        MOVING_UPDATE_OTHER("simulation", "movingObjects", "callbacks", "update", "other"),
        MOVING_POSTUPDATE_PLAYER("simulation", "movingObjects", "callbacks", "postupdate", "player"),
        MOVING_POSTUPDATE_VEHICLE("simulation", "movingObjects", "callbacks", "postupdate", "vehicle"),
        MOVING_POSTUPDATE_ANIMAL("simulation", "movingObjects", "callbacks", "postupdate", "animal"),
        MOVING_POSTUPDATE_ZOMBIE("simulation", "movingObjects", "callbacks", "postupdate", "zombie"),
        MOVING_POSTUPDATE_OTHER("simulation", "movingObjects", "callbacks", "postupdate", "other"),
        MAP_SAVE_COMPLETIONS("map", "saveCompletions"),
        MOVING_POST("simulation", "movingObjects", "postupdate"),
        SPOTTED_ROOMS("simulation", "spottedRooms"),
        ITEMS("simulation", "items"),
        ISO_OBJECTS("simulation", "isoObjects"),
        GAME_TIME("simulation", "misc", "gameTime"),
        RAIN("simulation", "misc", "rain"),
        META("simulation", "misc", "meta"),
        ANIMALS_SYNC("simulation", "animals", "sync"),
        RADIO("simulation", "radio"),
        NATIVE_PATH_MAIN("simulation", "pathfinding", "nativeMain"),
        JAVA_PATH_MAIN("simulation", "pathfinding", "javaMain"),
        HUTCHES("simulation", "hutches"),
        FISHING("simulation", "fishing"),
        CONTACTS("simulation", "contacts"),
        WORLD_PHYSICS("simulation", "physics"),
        BUILDINGS("simulation", "buildings"),
        WORLD_DATABASES("simulation", "databases"),
        WORLD_MAINTENANCE("simulation", "worldMaintenance"),
        ENTITY("simulation", "entities"),
        STATIC_UPDATERS("simulation", "staticUpdaters"),
        CORPSES("simulation", "corpses"),
        SIM_MISC("simulation", "misc"),
        MANAGERS("simulation", "managers"),
        FIRE("simulation", "fire"),
        WORLD_SOUNDS("simulation", "worldSounds"),
        SCRIPTS("simulation", "misc", "scripts"),
        VIRTUAL_ZOMBIES("simulation", "zombies", "virtual"),
        ZOMBIE_POPULATION("simulation", "zombies", "population"),
        COLLISION_MAIN("simulation", "collision", "main"),
        LOOT("simulation", "lootRespawn"),
        CLIMATE("simulation", "climate"),
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
            recordNanos(System.nanoTime() - started);
        }

        void recordNanos(long nanos) {
            totalNanos.add(nanos);
            maxNanos.accumulate(nanos);
            calls.increment();
            TickDiagnostics.recordPhase(this, nanos);
        }

        List<String> path() { return path; }
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
