package cloud.apocalipse.pz.agent.integration.probe;

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
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Drives native streaming on the server thread and measures the same boundaries in both A/B runs. */
public final class StreamingProbe {
    private static final int CYCLES = 6;
    private static final int HOLD_FRAMES = 3;
    private static final int FIRST_X = 165;
    private static final int FIRST_Y = 155;
    private static final int SIDE = 2;
    private static final String MAP = "zombie.network.ServerMap";
    private static final String CELL = MAP + "$ServerCell";
    private static final String CHUNK = "zombie.iso.IsoChunk";
    private static final String LOADER = "zombie.network.ServerChunkLoader";
    private static final String SAVE_JOB = LOADER + "$SaveUnloadedTask";
    private static final String SAVE = "chunk.saveWorker";
    private static final String RELEASE = "chunk.saveRelease";
    private static final String LOAD = "cell.load";
    private static final String LOAD_HANDOFF = "cell.loadQueueToRecalcQueue";
    private static final String RECALC_HANDOFF = "cell.recalcQueueToIntegration";
    private static final String UNLOAD = "cell.unload";
    private static final String DETACH = "chunk.detach";
    private static final String ENQUEUE = "chunk.saveEnqueue";

    private static Path directory;
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
        AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(named(MAP))
            .transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Preupdate.class).on(named("preupdate").and(hasDescriptor("()V"))))
                .visit(Advice.to(Postupdate.class).on(named("postupdate").and(hasDescriptor("()V")))));
        for (Target target : List.of(
                new Target("map.relevance", MAP, "loadOrKeepRelevent", "(II)V"),
                new Target(LOAD, CELL, "Load2"),
                new Target("cell.integrate", CELL, "RecalcAll2"),
                new Target("cell.loadVehicles", CELL, "loadVehicles"),
                new Target(UNLOAD, CELL, "Unload"),
                new Target("chunk.integrate", CHUNK, "doLoadGridsquare"),
                new Target(DETACH, CHUNK, "removeFromWorld"),
                new Target(ENQUEUE, LOADER, "addSaveUnloadedJob", "(Lzombie/iso/IsoChunk;)V"),
                new Target("map.saveCompletions", LOADER, "updateSaved"),
                new Target(SAVE, SAVE_JOB, "save"),
                new Target(RELEASE, SAVE_JOB, "release"),
                new Target("los.suspend", "zombie.network.ServerLOS", "suspend"),
                new Target("los.resume", "zombie.network.ServerLOS", "resume"))) {
            builder = builder.type(named(target.type())).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(PhaseName.class, target.phase())
                    .to(Timing.class).on(named(target.method()).and(hasDescriptor(target.descriptor())))));
        }
        builder.type(named(LOADER)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(Advice.to(LoadQueued.class).on(named("addJob")
                .and(hasDescriptor("(Lzombie/network/ServerMap$ServerCell;)V"))))
            .visit(Advice.to(RecalcQueued.class).on(named("addRecalcJob")
                .and(hasDescriptor("(Lzombie/network/ServerMap$ServerCell;)V")))))
            .type(named(CELL)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(IntegrationStarted.class).on(named("Load2").and(hasDescriptor("()V")))))
            .installOn(instrumentation);
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
        @Advice.OnMethodEnter public static long enter() { return start("map.postupdate"); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object map, @Advice.Enter long started,
                                @Advice.Thrown Throwable failure) {
            finish("map.postupdate", started, failure);
            if (failure == null) afterPostupdate(map);
        }
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
        return active != null && (Thread.currentThread() == owner || SAVE.equals(phase)) ? System.nanoTime() : 0;
    }

    public static void finish(String phase, long started, Throwable failure) {
        Cycle cycle = active;
        if (started != 0 && cycle != null) cycle.record(phase, System.nanoTime() - started, failure);
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
            }
            require(GameServer.Players.isEmpty() && engine.connections.isEmpty(),
                "Streaming fixture requires an empty server");
            if (active == null) {
                require(countChunks(map, true) == 0, "Fixture cells were already present");
                active = new Cycle(completed.size());
            }
            Cycle cycle = active;
            if (cycle.holdFrames < HOLD_FRAMES) {
                // Native relevance is cleared by preupdate. Renew before postupdate can cancel loading.
                for (int x = FIRST_X; x < FIRST_X + SIDE; x++) {
                    for (int y = FIRST_Y; y < FIRST_Y + SIDE; y++) {
                        map.loadOrKeepRelevent(x - map.getMinX(), y - map.getMinY());
                    }
                }
            } else if (cycle.released == 0) {
                cycle.released = System.nanoTime();
            }
        } catch (Exception failure) {
            failed(failure);
        }
    }

    public static void afterPostupdate(Object serverMap) {
        Cycle cycle = active;
        if (cycle == null) return;
        ServerMap map = (ServerMap) serverMap;
        try {
            long now = System.nanoTime();
            require(cycle.failure == null, String.valueOf(cycle.failure));
            require(now - cycle.started < TimeUnit.SECONDS.toNanos(30), "Streaming cycle timed out");
            if (cycle.released == 0) {
                int chunks = countChunks(map, false);
                if (chunks == 0) {
                    require(cycle.holdFrames == 0, "Cell disappeared while relevance was renewed");
                    return;
                }
                if (cycle.holdFrames == 0) {
                    cycle.loaded = now;
                    cycle.chunks = chunks;
                }
                require(chunks == cycle.chunks, "Chunk coverage changed while holding relevance");
                cycle.holdFrames++;
                return;
            }
            if (!cellsAbsent(map)) return;
            if (cycle.detached == 0) cycle.detached = now;
            if (cycle.count(RELEASE) < cycle.chunks) return;
            for (String phase : List.of(LOAD_HANDOFF, RECALC_HANDOFF, LOAD, UNLOAD)) {
                require(cycle.count(phase) == SIDE * SIDE, "Expected one " + phase + " per fixture cell");
            }
            for (String phase : List.of("chunk.integrate", DETACH, ENQUEUE, SAVE, RELEASE)) {
                require(cycle.count(phase) == cycle.chunks, phase + " count disagrees with loaded chunks");
            }
            cycle.record("wall.requestToLoaded", cycle.loaded - cycle.started, null);
            cycle.record("wall.releaseToDetached", cycle.detached - cycle.released, null);
            cycle.record("wall.releaseToSaveDrained", now - cycle.released, null);
            completed.add(cycle);
            active = null;
            if (completed.size() == CYCLES) {
                writeCsv();
                Files.writeString(directory.resolve("streaming-complete"), Integer.toString(CYCLES));
                done = true;
            }
        } catch (Exception failure) {
            failed(failure);
        }
    }

    private static int countChunks(ServerMap map, boolean requireAbsent) {
        int total = 0;
        for (int x = FIRST_X; x < FIRST_X + SIDE; x++) {
            for (int y = FIRST_Y; y < FIRST_Y + SIDE; y++) {
                var cell = map.getCell(x - map.getMinX(), y - map.getMinY());
                if (requireAbsent) {
                    require(cell == null, "Fixture cell was already present: " + x + "," + y);
                    continue;
                }
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
        }
        return total;
    }

    private static boolean cellsAbsent(ServerMap map) {
        for (int x = FIRST_X; x < FIRST_X + SIDE; x++) {
            for (int y = FIRST_Y; y < FIRST_Y + SIDE; y++) {
                if (map.getCell(x - map.getMinX(), y - map.getMinY()) != null) return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void failed(Exception failure) {
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
                csv.append(cycle.index).append(',').append(cycle.index == 0).append(',').append(SIDE * SIDE)
                    .append(',').append(cycle.chunks).append(',').append(entry.getKey()).append(',')
                    .append(stats.calls).append(',').append(stats.total).append(',').append(stats.max).append('\n');
            }
        }
        Files.writeString(directory.resolve("streaming.csv"), csv);
    }

    private static final class Stats {
        long calls, total, max;
    }

    private static final class Cycle {
        final int index;
        final long started = System.nanoTime();
        final Map<String, Stats> metrics = new LinkedHashMap<>();
        final Map<Object, Long> loading = new IdentityHashMap<>();
        final Map<Object, Long> recalculating = new IdentityHashMap<>();
        volatile String failure;
        int holdFrames, chunks;
        long loaded, released, detached;

        Cycle(int index) { this.index = index; }

        synchronized void record(String phase, long elapsed, Throwable thrown) {
            var stats = metrics.computeIfAbsent(phase, key -> new Stats());
            stats.calls++;
            stats.total += elapsed;
            stats.max = Math.max(stats.max, elapsed);
            if (thrown != null) failure = phase + ": " + thrown;
        }

        synchronized long count(String phase) {
            Stats stats = metrics.get(phase);
            return stats == null ? 0 : stats.calls;
        }
    }
}
