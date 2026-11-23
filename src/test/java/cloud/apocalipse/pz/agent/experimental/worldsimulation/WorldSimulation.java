package cloud.apocalipse.pz.agent.experimental.worldsimulation;

import cloud.apocalipse.pz.agent.experimental.worldsimulation.features.EngineDoorSimulation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;

/** Owns the fixture windows and the test-only simulation worker lifecycle. */
public final class WorldSimulation {
    public static final String PROPERTY = "pz.it.experimentalWorldSimulation";
    public static final String REPORT = "experimental-world-simulation.csv";
    private static final int WARMUP = 20;
    static final int WINDOW_FRAMES = 32;
    static final int WINDOWS_PER_CYCLE = 2;
    private static final int CORRECTNESS_FRAMES = WINDOWS_PER_CYCLE * WINDOW_FRAMES;
    private static final List<Window> windows = new ArrayList<>();
    private static Window window;
    private static Thread owner;
    private static boolean active;
    private static long frameStarted;

    private WorldSimulation() {}

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static AgentBuilder configure(AgentBuilder builder) {
        return EngineDoorSimulation.configure(builder);
    }

    public static boolean isActive() {
        return isOwner() && active;
    }

    public static boolean isOwner() {
        return Thread.currentThread() == owner;
    }

    public static boolean asynchronous() {
        return window == null || window.asynchronous;
    }

    public static boolean correctnessScenario() {
        return isActive() && window == null;
    }

    public static void frame(int cycle, int held, boolean released) throws Exception {
        owner = Thread.currentThread();
        if (!released && cycle == 0 && held >= WARMUP && held < WARMUP + CORRECTNESS_FRAMES) {
            active = true;
            return;
        }

        int offset = held - WARMUP;
        int block = offset / WINDOW_FRAMES;
        if (released || cycle == 0 || offset < 0 || block >= WINDOWS_PER_CYCLE) {
            if (active) {
                active = false;
                drainWindow();
            }
            window = null;
            return;
        }
        if (window == null || window.cycle != cycle || window.block != block) {
            active = false;
            drainWindow();
            boolean asynchronous = (block == 1) ^ (cycle % 2 == 0);
            window = new Window(cycle, block, asynchronous);
            windows.add(window);
        }
        active = true;
        frameStarted = System.nanoTime();
    }

    public static void endFrame() {
        if (isActive() && window != null) window.frameNanos.add(System.nanoTime() - frameStarted);
    }

    private static void drainWindow() {
        long started = System.nanoTime();
        int drained = EngineDoorSimulation.drain();
        if (window != null) {
            window.drained = drained;
            window.drainNanos = System.nanoTime() - started;
        }
    }

    public static long beginScheduler() {
        return isActive() && window != null ? System.nanoTime() : 0;
    }

    public static void endScheduler(long started) {
        if (started != 0) window.schedulerNanos.add(System.nanoTime() - started);
    }

    public static void record(boolean admitted, int consumed, boolean busy) {
        if (window == null) return;
        window.calls++;
        if (admitted) window.admitted++;
        window.consumed += consumed;
        if (busy) window.busy++;
    }

    public static void write(Path directory) throws IOException {
        var csv = new StringBuilder("cycle,block,arm,frames,calls,admitted,consumed,busy,"
            + "schedulerP50Nanos,schedulerP95Nanos,frameSamples,simulationSpanP95Nanos,drained,drainNanos\n");
        for (Window row : windows) {
            row.schedulerNanos.sort(Long::compare);
            row.frameNanos.sort(Long::compare);
            csv.append(row.cycle).append(',').append(row.block).append(',')
                .append(row.asynchronous ? "worker" : "inline").append(',')
                .append(row.schedulerNanos.size()).append(',').append(row.calls).append(',')
                .append(row.admitted).append(',').append(row.consumed).append(',').append(row.busy)
                .append(',').append(percentile(row.schedulerNanos, 0.50))
                .append(',').append(percentile(row.schedulerNanos, 0.95))
                .append(',').append(row.frameNanos.size()).append(',').append(percentile(row.frameNanos, 0.95))
                .append(',').append(row.drained).append(',').append(row.drainNanos).append('\n');
        }
        Files.writeString(directory.resolve(REPORT), csv);
        Files.writeString(directory.resolve("experimental-world-simulation-correctness.txt"),
            EngineDoorSimulation.correctnessEvidence());
    }

    public static void stop() {
        active = false;
        window = null;
        EngineDoorSimulation.stop();
    }

    private static long percentile(List<Long> samples, double fraction) {
        return samples.isEmpty() ? 0 : samples.get((int) Math.ceil(fraction * samples.size()) - 1);
    }

    private static final class Window {
        final int cycle;
        final int block;
        final boolean asynchronous;
        final List<Long> schedulerNanos = new ArrayList<>();
        final List<Long> frameNanos = new ArrayList<>();
        int calls, admitted, consumed, busy, drained;
        long drainNanos;

        Window(int cycle, int block, boolean asynchronous) {
            this.cycle = cycle;
            this.block = block;
            this.asynchronous = asynchronous;
        }

    }
}
