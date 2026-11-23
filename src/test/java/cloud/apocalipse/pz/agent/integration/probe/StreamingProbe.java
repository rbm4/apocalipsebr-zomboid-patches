package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.experimental.worldsimulation.WorldSimulation;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.optimization.streaming.CellBorderModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import zombie.iso.IsoGridSquare;
import zombie.SandboxOptions;
import zombie.SystemDisabler;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Drives native streaming on the server thread and measures the same boundaries in both A/B runs. */
public final class StreamingProbe {
    private static final boolean EXPERIMENTAL_WORLD_SIMULATION = Boolean.getBoolean("pz.it.experimentalWorldSimulation");
    private static final int CHURN_BATCH = 9;
    private static final int CHURN_FRAMES = 10;
    private static final List<Target> VEHICLE_TARGETS = List.of(
        new Target("vehicle.load.main", "zombie.vehicles.VehiclesDB2", "loadChunkMain", "(Lzombie/iso/IsoChunk;)V"),
        new Target("vehicle.load.sql", "zombie.vehicles.VehiclesDB2$SQLStore", "loadChunk", "(Lzombie/iso/IsoChunk;Lzombie/vehicles/VehiclesDB2$ThrowingBiConsumer;)V"),
        new Target("vehicle.load.materialize", "zombie.vehicles.VehiclesDB2$WorldStreamerThread", "vehicleLoaded", "(Lzombie/iso/IsoChunk;Lzombie/vehicles/VehiclesDB2$VehicleBuffer;)V"),
        new Target("vehicle.worker.load", "zombie.vehicles.VehiclesDB2$QueueLoadChunk", "processWorldStreamer", "()V"),
        new Target("vehicle.worker.sql", "zombie.vehicles.VehiclesDB2$SQLStore", "loadChunk",
            "(IILzombie/vehicles/VehiclesDB2$ThrowingConsumer;)V"),
        new Target("vehicle.worker.decode", "zombie.vehicles.VehiclesDB2$QueueLoadChunk", "vehicleLoaded",
            "(Lzombie/vehicles/VehiclesDB2$VehicleBuffer;)V"),
        new Target("vehicle.load.applyQueue", "zombie.vehicles.VehiclesDB2$QueueLoadChunk", "processMain", "()V"),
        new Target("vehicle.save.enqueue", "zombie.vehicles.VehiclesDB2", "updateVehicle", "(Lzombie/vehicles/BaseVehicle;)V"),
        new Target("vehicle.worker.save", "zombie.vehicles.VehiclesDB2$QueueUpdateVehicle", "processWorldStreamer", "()V"),
        new Target("vehicle.parts", "zombie.vehicles.BaseVehicle", "updateParts", "()V"),
        new Target("vehicle.manager", "zombie.vehicles.VehicleManager", "serverUpdate", "()V"),
        new Target("vehicle.physics", "zombie.core.physics.WorldSimulation", "update", "()V"),
        new Target("vehicle.physics.prepare", "zombie.core.physics.WorldSimulation", "updateVehiclePhysics", "()V"));
    private static boolean originalSpawnOverride;
    private static final String MAP = "zombie.network.ServerMap";
    private static final String CELL = MAP + "$ServerCell";
    private static final String CHUNK = "zombie.iso.IsoChunk";
    private static final String LOADER = "zombie.network.ServerChunkLoader";
    private static final String SAVE_JOB = LOADER + "$SaveUnloadedTask";
    private static final String SAVE = "chunk.saveWorker";
    private static final String WRITE = "chunk.writeWorker";
    private static final String STORAGE_WRITE = "chunk.storageWrite";
    private static final String RELEASE = "chunk.saveRelease";
    private static final String LOAD = "cell.load";
    private static final String LOAD_HANDOFF = "cell.loadQueueToRecalcQueue";
    private static final String RECALC_HANDOFF = "cell.recalcQueueToIntegration";
    private static final String UNLOAD = "cell.unload";
    private static final String DETACH = "chunk.detach";
    private static final String LOAD_WORKER = "chunk.loadWorker";
    private static final String GENERATE = "chunk.generate";
    private static final String READ_SAVE = "chunk.readSave";
    private static final String SAFE_READ = "chunk.safeRead";
    private static final String READ_PENDING = "chunk.readPendingSave";
    private static final String ENQUEUE = "chunk.saveEnqueue";

    private static Path directory;
    private static StreamingWorkload workload;
    private static ChunkStorageArm storageArm;
    private static boolean movingScenario;
    private static MovingObjectLoad movingLoad;
    private static boolean compareVehicleCadence;
    private static boolean profileChunkLifecycle;
    private static long nextRequestPoll;
    private static boolean done;
    private static Thread owner;
    private static UdpEngine engine;
    private static volatile Cycle active;
    private static final List<Cycle> completed = new ArrayList<>();

    private record Target(String phase, String type, String method, String descriptor) {
        Target(String phase, String type, String method) { this(phase, type, method, "()V"); }
    }

    static void install(Path output, Instrumentation instrumentation) {
        directory = output;
        workload = StreamingWorkload.from(System.getProperties());
        storageArm = ChunkStorageArm.parse(System.getProperty(ChunkStorageArm.PROPERTY));
        movingScenario = Boolean.getBoolean("pz.it.movingObjects");
        compareVehicleCadence = Boolean.getBoolean(VehicleCadenceProbe.COMPARE_PROPERTY);
        profileChunkLifecycle = Boolean.getBoolean(ChunkLifecycleProbe.PROFILE_PROPERTY);
        AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(named(MAP))
            .transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Preupdate.class).on(named("preupdate").and(hasDescriptor("()V"))))
                .visit(Advice.to(Postupdate.class).on(named("postupdate").and(hasDescriptor("()V")))));
        var targets = new ArrayList<>(List.of(
                new Target("map.relevance", MAP, "loadOrKeepRelevent", "(II)V"),
                new Target(LOAD, CELL, "Load2"),
                new Target("cell.integrate", CELL, "RecalcAll2"),
                new Target("cell.loadVehicles", CELL, "loadVehicles"),
                new Target(UNLOAD, CELL, "Unload"),
                new Target(LOAD_WORKER, CHUNK, "LoadChunk", "(IILjava/nio/ByteBuffer;)Z"),
                new Target(GENERATE, CHUNK, "LoadBrandNew", "(II)Z"),
                new Target(READ_SAVE, CHUNK, "LoadFromDisk"),
                new Target(SAFE_READ, CHUNK, "SafeRead", "(IILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;"),
                new Target(READ_PENDING, CHUNK, "LoadFromBuffer", "(IILjava/nio/ByteBuffer;)Z"),
                new Target("chunk.integrate", CHUNK, "doLoadGridsquare"),
                new Target(DETACH, CHUNK, "removeFromWorld"),
                new Target(ENQUEUE, LOADER, "addSaveUnloadedJob", "(Lzombie/iso/IsoChunk;)V"),
                new Target("map.saveCompletions", LOADER, "updateSaved"),
                new Target(SAVE, SAVE_JOB, "save"),
                new Target(WRITE, CHUNK, "SafeWrite", "(IILjava/nio/ByteBuffer;)V"),
                new Target(RELEASE, SAVE_JOB, "release"),
                new Target("los.suspend", "zombie.network.ServerLOS", "suspend"),
                new Target("los.resume", "zombie.network.ServerLOS", "resume")));
        if (storageArm != null) {
            targets.add(new Target(STORAGE_WRITE, LOADER, "writeChunk", "(Lzombie/network/ClientChunkRequest$Chunk;)V"));
        }
        if (workload.vehicleStress() || Boolean.getBoolean(MovingObjectLoad.PROPERTY)) {
            targets.addAll(VEHICLE_TARGETS);
            builder = builder.type(named("zombie.vehicles.BaseVehicle"))
                .transform((dynamic, type, loader, module, domain) -> dynamic
                    .visit(Advice.to(VehicleUpdate.class).on(named("update").and(hasDescriptor("()V")))))
                .type(named("zombie.core.physics.WorldSimulation")).transform((dynamic, type, loader, module, domain) ->
                    dynamic.visit(OptimizationVisitor.method("updatePhysic", "()V", BulletStepVisitor::new)));
        }
        for (Target target : targets) {
            Class<?> advice = target.phase().startsWith("vehicle.worker.") ? VehicleWorkerTiming.class : Timing.class;
            builder = builder.type(named(target.type())).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(PhaseName.class, target.phase())
                    .to(advice).on(named(target.method()).and(hasDescriptor(target.descriptor())))));
        }
        if (Boolean.getBoolean(MovingObjectLoad.PROPERTY)) {
            builder = builder.type(named("zombie.MovingObjectUpdateScheduler"))
                .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(SchedulerSample.class)
                    .on(named("update").and(hasDescriptor("()V")))));
        }
        builder = EntityAllocations.configure(builder);
        if (movingScenario) builder = MovingObjectSpike.configure(builder, output);
        if (compareVehicleCadence) builder = VehicleCadenceProbe.configure(builder);
        if (EXPERIMENTAL_WORLD_SIMULATION) builder = WorldSimulation.configure(builder);
        if (profileChunkLifecycle) builder = ChunkLifecycleProbe.configure(builder, workload.chunkComparison());
        builder = UnloadBreakdownProbe.configure(builder);
        builder = UnloadSamplingProbe.configure(builder);
        builder = BucketUnloadProbe.configure(builder);
        builder.type(named(LOADER)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(Advice.to(LoadQueued.class).on(named("addJob")
                .and(hasDescriptor("(Lzombie/network/ServerMap$ServerCell;)V"))))
            .visit(Advice.to(RecalcQueued.class).on(named("addRecalcJob")
                .and(hasDescriptor("(Lzombie/network/ServerMap$ServerCell;)V")))))
            .type(named(CELL)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(IntegrationStarted.class).on(named("Load2").and(hasDescriptor("()V"))))
                .visit(OptimizationVisitor.method("RecalcAll2", "()V", BorderOracleVisitor::new)))
            .type(named(CHUNK)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(OptimizationVisitor.method("SafeRead", ChunkReadProbe.SAFE_READ_DESCRIPTOR, ChunkReadProbe::new)))
            .installOn(instrumentation);
    }

    /** The production transformer ran first. Check its answers on the generation cycle only. */
    private static final class BorderOracleVisitor extends MethodVisitor {
        BorderOracleVisitor(MethodVisitor downstream) { super(Opcodes.ASM9, downstream); }

        @Override
        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC && owner.equals(CellBorderModule.class.getName().replace('.', '/'))
                    && method.equals("borderSquare")) {
                OptimizationVisitor.callStatic(mv, StreamingProbe.class, "verifyBorderSquare", descriptor);
            } else {
                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
            }
        }
    }

    public static IsoGridSquare verifyBorderSquare(ServerMap map, int x, int y, int z, ServerMap.ServerCell cell) {
        var actual = CellBorderModule.borderSquare(map, x, y, z, cell);
        Cycle cycle = active;
        if (cycle != null && cycle.index == 0) {
            long started = System.nanoTime();
            if (actual != map.getGridSquare(x, y, z)) {
                cycle.failure = "Border lookup changed square identity at " + x + "," + y + "," + z;
            }
            cycle.record(actual == null ? "cell.borderOracleMissing" : "cell.borderOraclePresent",
                System.nanoTime() - started, null);
        }
        return actual;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface PhaseName {}

    public static class Timing {
        @Advice.OnMethodEnter
        public static long enter(@PhaseName String phase) { return start(phase); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started, @PhaseName String phase,
                                @Advice.Thrown Throwable failure) {
            finish(phase, started, failure);
        }
    }

    private static final class BulletStepVisitor extends MethodVisitor {
        private int matches;

        BulletStepVisitor(MethodVisitor downstream) {
            super(Opcodes.ASM9, downstream);
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC && owner.equals("zombie/core/physics/Bullet")
                    && name.equals("stepSimulation") && descriptor.equals("(FIF)V")) {
                OptimizationVisitor.callStatic(mv, StreamingProbe.class, "stepPhysics", descriptor);
                matches++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("native Bullet substep", matches, 1);
            super.visitEnd();
        }
    }

    public static void stepPhysics(float delta, int maximumSubsteps, float fixedStep) {
        long started = start("vehicle.physics.step");
        try {
            if (movingLoad != null && onStreamingOwner()) movingLoad.recordBulletStep();
            zombie.core.physics.Bullet.stepSimulation(delta, maximumSubsteps, fixedStep);
        } finally {
            finish("vehicle.physics.step", started, null);
        }
    }

    private record WorkerSample(Cycle cycle, long started) {}

    public static Object beginVehicleWorker() {
        Cycle cycle = active;
        return cycle == null || Thread.currentThread() == owner ? null : new WorkerSample(cycle, System.nanoTime());
    }

    public static void finishVehicleWorker(Object sample, String phase, Throwable error) {
        if (sample instanceof WorkerSample worker) {
            worker.cycle.record(phase, System.nanoTime() - worker.started, error);
        }
    }

    public static class VehicleWorkerTiming {
        @Advice.OnMethodEnter public static Object enter() { return beginVehicleWorker(); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter Object sample, @PhaseName String phase, @Advice.Thrown Throwable error) {
            finishVehicleWorker(sample, phase, error);
        }
    }

    public static class SchedulerSample {
        @Advice.OnMethodEnter
        public static void enter() { sampleScheduler(true); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() { sampleScheduler(false); }
    }

    public static void sampleScheduler(boolean starting) {
        if (movingLoad == null || !onStreamingOwner()) return;
        if (starting) movingLoad.beginScheduler();
        else movingLoad.endScheduler();
    }

    public static class VehicleUpdate {
        @Advice.OnMethodEnter public static long enter(@Advice.This Object vehicle) {
            return beginVehicleUpdate(vehicle);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object vehicle, @Advice.Enter long started, @Advice.Thrown Throwable error) {
            finishVehicleUpdate(vehicle, started, error);
        }
    }

    public static long beginVehicleUpdate(Object vehicle) {
        Cycle cycle = active;
        if (cycle == null || Thread.currentThread() != owner || cycle.loaded == 0 || cycle.released != 0) return 0;
        cycle.steadyVehicles.put(vehicle, Boolean.TRUE);
        return System.nanoTime();
    }

    public static void finishVehicleUpdate(Object vehicle, long started, Throwable error) {
        Cycle cycle = active;
        if (started == 0 || cycle == null) return;
        long elapsed = System.nanoTime() - started;
        cycle.record("vehicle.steady.update", elapsed, error);
        if (movingLoad != null) movingLoad.recordVehicleUpdate(vehicle, elapsed);
        if (compareVehicleCadence) VehicleCadenceProbe.recordVehicleUpdate(vehicle, elapsed);
    }

    public static class Preupdate {
        @Advice.OnMethodEnter public static long enter() { return start("map.preupdate"); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object map, @Advice.Enter long started,
                                @Advice.Thrown Throwable failure) {
            finish("map.preupdate", started, failure);
            if (failure == null) afterPreupdate(map);
        }
    }

    public static class Postupdate {
        @Advice.OnMethodEnter public static long enter(@Advice.Local("unloadProfile") long unloadProfile) {
            UnloadSamplingProbe.beginPostupdate();
            unloadProfile = UnloadBreakdownProbe.beginPostupdate();
            sampleMapPostupdate(true);
            return start("map.postupdate");
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object map, @Advice.Enter long started,
                                @Advice.Thrown Throwable failure,
                                @Advice.Local("unloadProfile") long unloadProfile) {
            sampleMapPostupdate(false);
            finish("map.postupdate", started, failure);
            UnloadBreakdownProbe.endPostupdate(unloadProfile);
            UnloadSamplingProbe.endPostupdate();
            if (failure == null) afterPostupdate(map);
        }
    }

    public static void sampleMapPostupdate(boolean starting) {
        if (movingLoad == null || !onStreamingOwner()) return;
        if (starting) movingLoad.beginMapPostupdate();
        else movingLoad.endMapPostupdate();
    }

    // Advice signatures stay free of game types so Byte Buddy can read them during class definition.
    public static class LoadQueued {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) Object cell) { loadQueued(cell); }
    }

    public static class RecalcQueued {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) Object cell) { recalcQueued(cell); }
    }

    public static class IntegrationStarted {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object cell) { integrationStarted(cell); }
    }

    public static void loadQueued(Object cell) {
        Cycle cycle = active;
        if (cycle != null) cycle.loading.put(cell, System.nanoTime());
    }

    public static void recalcQueued(Object cell) {
        Cycle cycle = active;
        if (cycle == null) return;
        long now = System.nanoTime();
        Long queued = cycle.loading.remove(cell);
        if (queued != null) {
            cycle.record(LOAD_HANDOFF, now - queued, null);
            cycle.recalculating.put(cell, now);
        }
    }

    public static void integrationStarted(Object cell) {
        Cycle cycle = active;
        if (cycle == null) return;
        Long queued = cycle.recalculating.remove(cell);
        if (queued != null) cycle.record(RECALC_HANDOFF, System.nanoTime() - queued, null);
    }

    public static long start(String phase) {
        if (active == null) return 0;
        Thread thread = Thread.currentThread();
        if (LOAD_WORKER.equals(phase) || GENERATE.equals(phase) || READ_SAVE.equals(phase)
                || SAFE_READ.equals(phase) || READ_PENDING.equals(phase)) {
            return "LoadChunk".equals(thread.getName()) ? System.nanoTime() : 0;
        }
        if (WRITE.equals(phase) || STORAGE_WRITE.equals(phase)) {
            return "SaveChunk".equals(thread.getName()) ? System.nanoTime() : 0;
        }
        return thread == owner || SAVE.equals(phase) ? System.nanoTime() : 0;
    }

    static long startSavedChunkIo() { return active == null ? 0 : System.nanoTime(); }

    static void recordSavedChunkCpu(long nanos) {
        Cycle cycle = active;
        if (cycle != null) cycle.record("chunk.io.openCpu", nanos, null);
    }

    public static void recordReadSize(int bytes) {
        Cycle cycle = active;
        if (cycle != null && ChunkReadProbe.isLoader()) cycle.readBytes.add(bytes);
    }

    public static void finish(String phase, long started, Throwable failure) {
        Cycle cycle = active;
        if (started != 0 && cycle != null) cycle.record(phase, System.nanoTime() - started, failure);
    }

    static boolean onStreamingOwner() {
        return active != null && Thread.currentThread() == owner;
    }

    static int activeCycle() {
        return onStreamingOwner() ? active.index : -1;
    }

    public static void afterPreupdate(Object serverMap) {
        if (done) return;
        ServerMap map = (ServerMap) serverMap;
        try {
            if (owner == null) {
                long now = System.nanoTime();
                if (nextRequestPoll != 0 && now - nextRequestPoll < 0) return;
                nextRequestPoll = now + TimeUnit.MILLISECONDS.toNanos(250);
                if (!Files.isRegularFile(directory.resolve("streaming-request"))) return;
                // The runner compiles alongside a GameServer double with a different field descriptor.
                engine = (UdpEngine) GameServer.class.getField("udpEngine").get(null);
                owner = Thread.currentThread();
                if (Boolean.getBoolean(MovingObjectLoad.PROPERTY)) movingLoad = new MovingObjectLoad();
                UnloadScanChecks.verify(map, directory);
                UnloadSamplingProbe.start();
                if (workload.vehicleStress()) {
                    originalSpawnOverride = SystemDisabler.doVehiclesEverywhere;
                    require(SandboxOptions.instance.enableVehicles.getValue()
                        && SandboxOptions.instance.carSpawnRate.getValue() == 5, "Expected profile vehicle sandbox values");
                    SystemDisabler.doVehiclesEverywhere = true;
                }
            }
            require(engine.connections.isEmpty()
                && (movingLoad == null ? GameServer.Players.isEmpty() : movingLoad.ownsPlayers()),
                "Streaming fixture requires an isolated server and only fixture-owned players");
            if (active == null) {
                if (storageArm != null && !Files.isRegularFile(directory.resolve("storage-start-" + completed.size()))) {
                    return;
                }
                require(cellsAbsent(map, workload.cells()), "Fixture cells were already present");
                active = new Cycle(completed.size());
            }
            Cycle cycle = active;
            if (movingScenario && cycle.loaded != 0 && Files.isRegularFile(directory.resolve("moving-object-arm"))) {
                MovingObjectSpike.arm();
            }
            boolean waitForTelemetry = movingScenario && !Files.isRegularFile(directory.resolve("moving-object-release"));
            boolean waitForMovingLoad = movingLoad != null && !movingLoad.finished();
            if (waitForMovingLoad && cycle.loaded != 0) movingLoad.drive();
            if (cycle.holdFrames < workload.holdFrames() || waitForTelemetry || waitForMovingLoad) {
                // Native relevance is cleared by preupdate. Renew before postupdate can cancel loading.
                renewRelevance(map, cycle.cells);
            } else {
                if (cycle.released == 0) cycle.released = System.nanoTime();
                if (workload.vehicleStress()) {
                    int withdrawn = Math.min(cycle.cells.size(), (1 + cycle.churnFrames++ / CHURN_FRAMES) * CHURN_BATCH);
                    renewRelevance(map, cycle.cells.subList(withdrawn, cycle.cells.size()));
                }
            }
            if (compareVehicleCadence) VehicleCadenceProbe.frame(cycle.index, cycle.holdFrames, cycle.released != 0);
            if (EXPERIMENTAL_WORLD_SIMULATION) WorldSimulation.frame(cycle.index, cycle.holdFrames, cycle.released != 0);
        } catch (Exception failure) {
            failed(failure);
        }
    }

    public static void afterPostupdate(Object serverMap) {
        Cycle cycle = active;
        if (cycle == null) return;
        if (EXPERIMENTAL_WORLD_SIMULATION) WorldSimulation.endFrame();
        ServerMap map = (ServerMap) serverMap;
        try {
            long now = System.nanoTime();
            require(cycle.failure == null, String.valueOf(cycle.failure));
            int deadline = workload.cycleTimeoutSeconds() + (movingLoad == null ? 0 : 300);
            require(now - cycle.started < TimeUnit.SECONDS.toNanos(deadline), "Streaming cycle timed out");
            if (cycle.released == 0) {
                int chunks = countChunks(map, cycle.cells);
                if (chunks == 0) {
                    require(cycle.holdFrames == 0, "Cell disappeared while relevance was renewed");
                    return;
                }
                if (cycle.holdFrames == 0) {
                    cycle.loaded = now;
                    cycle.chunks = chunks;
                    if (movingScenario) Files.createFile(directory.resolve("moving-object-ready"));
                    if (cycle.index == 0 && new CellBorderModule().disabledReason() == null) {
                        verifyBorderFallbacks(map);
                    }
                }
                require(chunks == cycle.chunks, "Chunk coverage changed while holding relevance");
                if (cycle.index == 0 && AgentConfiguration.chunkObjectMutationsEnabled()) {
                    if (cycle.holdFrames == 0) {
                        ChunkObjectMutationProbe.add(groundSquare(map, cycle.cells.getFirst()),
                            groundSquare(map, cycle.cells.get(1)));
                    } else if (cycle.holdFrames == 1) {
                        ChunkObjectMutationProbe.changeAndRemove(directory);
                    }
                }
                if (movingLoad != null) {
                    if (cycle.holdFrames == 0) movingLoad.start(map, workload.positions());
                    else movingLoad.sample(directory);
                }
                if (cycle.holdFrames < workload.holdFrames()) cycle.holdFrames++;
                return;
            }
            if (!cellsAbsent(map, cycle.cells)) return;
            if (cycle.detached == 0) cycle.detached = now;
            if (cycle.count(RELEASE) < cycle.chunks) return;
            for (String phase : List.of(LOAD_HANDOFF, RECALC_HANDOFF, LOAD, UNLOAD)) {
                require(cycle.count(phase) == cycle.cells.size(), "Expected one " + phase + " per fixture cell");
            }
            for (String phase : List.of(LOAD_WORKER, "chunk.integrate", DETACH, ENQUEUE, SAVE, RELEASE)) {
                require(cycle.count(phase) == cycle.chunks, phase + " count disagrees with loaded chunks");
            }
            long generated = cycle.count(GENERATE);
            long saved = cycle.count(READ_SAVE);
            long buffer = cycle.count(READ_PENDING);
            boolean postgres = AgentConfiguration.postgresChunksEnabled();
            if (storageArm != null) {
                boolean first = cycle.index == 0;
                long expectedGenerated = first && !storageArm.seeded() ? cycle.chunks : 0;
                long expectedBuffer = postgres && !first ? cycle.chunks : 0;
                require(postgres == storageArm.postgres() && generated == expectedGenerated
                    && buffer == expectedBuffer && saved == cycle.chunks - expectedGenerated - expectedBuffer,
                    "Unexpected chunk load path for " + storageArm + ", cycle " + cycle.index);
                require(!postgres || cycle.count(WRITE) == 0, "PostgreSQL save must bypass disk writes");
                require(cycle.count(STORAGE_WRITE) == cycle.chunks, "Expected one storage write attempt per chunk");
            } else if (postgres) {
                require(cycle.count(WRITE) == 0, "PostgreSQL save must bypass disk writes");
                if (cycle.index == 0) {
                    require(generated == 1 && saved == cycle.chunks - 1 && buffer == 0,
                        "First PostgreSQL cycle must load the disk seed and generate one missing chunk");
                } else {
                    require(generated == 0 && saved == 0 && buffer == cycle.chunks,
                        "Migrated reload must pass all chunks to the native buffer decoder");
                }
            } else {
                require(buffer == 0, "Unexpected pending-save load in cycle " + cycle.index);
            }
            require(cycle.count(SAFE_READ) == saved, "Saved-chunk reads must go through SafeRead");
            for (String phase : ChunkReadProbe.phases()) {
                require(cycle.count(phase) == saved, phase + " count disagrees with saved-chunk reads");
            }
            if (ChunkReadProbe.tracksOpenCpu()) {
                require(cycle.count("chunk.io.openCpu") == saved, "Expected CPU samples for saved-chunk opens");
            }
            require(cycle.readBytes.size() == saved && cycle.readBytes.stream().allMatch(bytes -> bytes >= 17),
                "Expected a complete chunk header for each saved read");
            if (cycle.index == 0 && new CellBorderModule().disabledReason() == null) {
                require(cycle.count("cell.borderOracleMissing") > 0 && cycle.count("cell.borderOraclePresent") > 0,
                    "Expected transformed lookups to return both present and missing squares");
            }
            require(generated + saved + buffer == cycle.chunks, "Load path counts disagree with loaded chunks");
            require(storageArm != null || (cycle.index == 0 ? generated > 0 : generated == 0),
                "Expected generation only in the first cycle; cycle=" + cycle.index + ", generated=" + generated);
            cycle.record("wall.requestToLoaded", cycle.loaded - cycle.started, null);
            cycle.record("wall.releaseToDetached", cycle.detached - cycle.released, null);
            cycle.record("wall.releaseToSaveDrained", now - cycle.released, null);
            if (profileChunkLifecycle) ChunkLifecycleProbe.drain(cycle::recordSummary);
            if (workload.vehicleStress()) {
                require(cycle.steadyVehicles.size() >= 15, "Vehicle pressure too low: " + cycle.steadyVehicles.size());
                if (cycle.index > 0) {
                    require(cycle.count("vehicle.load.main") > 0
                        && cycle.count("vehicle.worker.load") > 0
                        && cycle.count("vehicle.worker.load") == cycle.count("vehicle.worker.sql")
                        && cycle.count("vehicle.worker.decode") > 0
                        && cycle.count("vehicle.load.applyQueue") > 0,
                        "Warm cycle did not exercise queued SQLite vehicle loads, decode and owner application");
                }
                cycle.vehicleCount = cycle.steadyVehicles.size();
                cycle.steadyVehicles.clear();
            }
            completed.add(cycle);
            active = null;
            if (storageArm != null) Files.createFile(directory.resolve("storage-drained-" + cycle.index));
            int cycles = storageArm == null ? workload.cycles() : storageArm.cycles(workload);
            if (movingScenario || movingLoad != null || completed.size() == cycles) {
                restoreVehicleSettings();
                writeCsv();
                UnloadBreakdownProbe.write(directory);
                UnloadSamplingProbe.finish(directory);
                BucketUnloadProbe.write(directory);
                if (compareVehicleCadence) VehicleCadenceProbe.write(directory);
                if (workload.chunkComparison()) ChunkLifecycleComparison.write(directory);
                if (EXPERIMENTAL_WORLD_SIMULATION) {
                    WorldSimulation.write(directory);
                    WorldSimulation.stop();
                }
                Files.createFile(directory.resolve("streaming-complete"));
                done = true;
            }
        } catch (Exception failure) {
            failed(failure);
        }
    }

    private static void verifyBorderFallbacks(ServerMap map) {
        var first = workload.cells().getFirst();
        var adjacent = workload.cells().get(1);
        var published = map.getCell(first.x() - map.getMinX(), first.y() - map.getMinY());
        var detached = new ServerMap.ServerCell();
        detached.wx = first.x();
        detached.wy = first.y();
        detached.isLoaded = true;
        var firstSquare = groundSquare(map, first);
        var adjacentSquare = groundSquare(map, adjacent);
        require(CellBorderModule.borderSquare(map, firstSquare.x, firstSquare.y, 0, detached) == firstSquare,
            "An unpublished cell must use the published map square");
        require(CellBorderModule.borderSquare(map, adjacentSquare.x, adjacentSquare.y, 0, published) == adjacentSquare,
            "An adjacent cell must use the global lookup");
        require(CellBorderModule.borderSquare(map, firstSquare.x, firstSquare.y, 32, published) == null,
            "An invalid floor must not return a square");
    }

    private static IsoGridSquare groundSquare(ServerMap map, StreamingWorkload.Cell cell) {
        for (int x = cell.x() * 64; x < (cell.x() + 1) * 64; x++) {
            for (int y = cell.y() * 64; y < (cell.y() + 1) * 64; y++) {
                var square = map.getGridSquare(x, y, 0);
                if (square != null) return square;
            }
        }
        throw new IllegalStateException("No ground square in fixture cell " + cell);
    }

    private static void renewRelevance(ServerMap map, List<StreamingWorkload.Cell> cells) {
        if (workload.positions().isEmpty()) {
            for (var cell : cells) {
                map.loadOrKeepRelevent(cell.x() - map.getMinX(), cell.y() - map.getMinY());
            }
        } else {
            for (var position : workload.positions()) {
                map.characterIn(position.chunkX(), position.chunkY(), StreamingWorkload.CHUNK_GRID_WIDTH);
            }
        }
    }

    private static int countChunks(ServerMap map, List<StreamingWorkload.Cell> cells) {
        int total = 0;
        for (var target : cells) {
            int x = target.x();
            int y = target.y();
            var cell = map.getCell(x - map.getMinX(), y - map.getMinY());
            if (cell == null || !cell.isLoaded) return 0;
            int count = 0;
            for (int dx = 0; dx < 8; dx++) {
                for (int dy = 0; dy < 8; dy++) {
                    if (map.getChunk(x * 8 + dx, y * 8 + dy) != null) count++;
                }
            }
            require(count > 0, "Integrated cell had no visible chunks: " + x + "," + y);
            total += count;
        }
        return total;
    }

    private static boolean cellsAbsent(ServerMap map, List<StreamingWorkload.Cell> cells) {
        for (var cell : cells) {
            if (map.getCell(cell.x() - map.getMinX(), cell.y() - map.getMinY()) != null) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void restoreVehicleSettings() {
        if (workload.vehicleStress()) SystemDisabler.doVehiclesEverywhere = originalSpawnOverride;
    }

    private static void failed(Exception failure) {
        if (movingLoad != null) {
            try {
                movingLoad.writeSamples(directory);
            } catch (IOException evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            try {
                movingLoad.cleanup();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        UnloadSamplingProbe.discard();
        if (compareVehicleCadence) VehicleCadenceProbe.stop();
        if (EXPERIMENTAL_WORLD_SIMULATION) {
            try {
                WorldSimulation.stop();
            } catch (RuntimeException shutdownFailure) {
                failure.addSuppressed(shutdownFailure);
            }
        }
        restoreVehicleSettings();
        active = null;
        done = true;
        try {
            writeCsv();
            Files.writeString(directory.resolve("streaming-error.txt"), failure.toString());
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    private static void writeCsv() throws IOException {
        var csv = new StringBuilder("cycle,warmup,cells,chunks,phase,calls,totalNanos,maxNanos\n");
        for (Cycle cycle : completed) {
            for (var entry : cycle.metrics.entrySet()) {
                Stats stats = entry.getValue();
                csv.append(cycle.index).append(',').append(cycle.index == 0).append(',').append(cycle.cells.size())
                    .append(',').append(cycle.chunks).append(',').append(entry.getKey()).append(',')
                    .append(stats.calls).append(',').append(stats.total).append(',').append(stats.max).append('\n');
            }
        }
        Files.writeString(directory.resolve("streaming.csv"), csv);
        if (workload.vehicleStress()) {
            var vehicles = new StringBuilder("cycle,cells,steadyFrames,uniqueVehicles,churnFrames\n");
            for (Cycle cycle : completed) {
                vehicles.append(cycle.index).append(',').append(cycle.cells.size()).append(',')
                    .append(cycle.holdFrames).append(',').append(cycle.vehicleCount).append(',')
                    .append(cycle.churnFrames).append('\n');
            }
            Files.writeString(directory.resolve("vehicle-pressure.csv"), vehicles);
        }
        var bytes = new StringBuilder("cycle,reads,totalBytes,minBytes,p50Bytes,p95Bytes,maxBytes\n");
        for (Cycle cycle : completed) {
            if (cycle.readBytes.isEmpty()) continue;
            int[] sorted = cycle.readBytes.stream().mapToInt(Integer::intValue).sorted().toArray();
            long total = java.util.Arrays.stream(sorted).asLongStream().sum();
            bytes.append(cycle.index).append(',').append(sorted.length).append(',').append(total)
                .append(',').append(sorted[0]).append(',').append(sorted[(sorted.length - 1) / 2])
                .append(',').append(sorted[(int) Math.ceil(.95 * sorted.length) - 1])
                .append(',').append(sorted[sorted.length - 1]).append('\n');
        }
        Files.writeString(directory.resolve("streaming-read-sizes.csv"), bytes);
    }

    private static final class Stats {
        long calls, total, max;
    }

    private static final class Cycle {
        final int index;
        final List<StreamingWorkload.Cell> cells;
        final Map<Object, Boolean> steadyVehicles = new IdentityHashMap<>();
        final long started = System.nanoTime();
        final Map<String, Stats> metrics = new LinkedHashMap<>();
        final List<Integer> readBytes = new ArrayList<>();
        final Map<Object, Long> loading = new IdentityHashMap<>();
        final Map<Object, Long> recalculating = new IdentityHashMap<>();
        volatile String failure;
        int holdFrames, chunks, churnFrames, vehicleCount;
        long loaded, released, detached;

        Cycle(int index) {
            this.index = index;
            cells = workload.cellsFor(index);
            if (workload.vehicleStress()) {
                for (Target target : VEHICLE_TARGETS) metrics.put(target.phase(), new Stats());
                metrics.put("vehicle.steady.update", new Stats());
                metrics.put("vehicle.physics.step", new Stats());
            }
            // Native save tasks can legitimately skip writing unchanged chunks.
            metrics.put(WRITE, new Stats());
        }

        synchronized void record(String phase, long elapsed, Throwable thrown) {
            var stats = metrics.computeIfAbsent(phase, key -> new Stats());
            stats.calls++;
            stats.total += elapsed;
            stats.max = Math.max(stats.max, elapsed);
            if (thrown != null) failure = phase + ": " + thrown;
        }

        synchronized void recordSummary(String phase, long calls, long total, long max) {
            var stats = new Stats();
            stats.calls = calls;
            stats.total = total;
            stats.max = max;
            metrics.put(phase, stats);
        }

        synchronized long count(String phase) {
            Stats stats = metrics.get(phase);
            return stats == null ? 0 : stats.calls;
        }
    }
}
