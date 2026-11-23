package cloud.apocalipse.pz.agent.integration.probe;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** One JFR window per unload-bearing postupdate, with optional native search accounting. */
public final class UnloadSamplingProbe {
    public static final String PROPERTY = "pz.it.unloadSampling";
    public static final String SEARCH_PROPERTY = "pz.it.unloadSearchWork";
    public static final String EVENT = "apocbr.UnloadPostupdate";
    public static final String RECORDING = "unload-sampling.jfr";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final boolean SEARCH = Boolean.getBoolean(SEARCH_PROPERTY);
    private static Recording recording;
    private static UnloadPostupdate window;
    private static int ordinal;
    private static int cellDepth;
    private static long startCpu;
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private UnloadSamplingProbe() {}

    @Name(EVENT)
    @StackTrace(false)
    public static final class UnloadPostupdate extends Event {
        public int cycle;
        public int postupdate;
        public int cells;
        public int chunks;
        public long ownerCpuNanos;
        public boolean searchEnabled;
        public long searchCalls;
        public long searchCandidates;
        public long searchNanos;
        public long searchIncomplete;
    }

    static AgentBuilder configure(AgentBuilder builder) {
        if (!ENABLED) return builder;
        builder = builder.type(named("zombie.network.ServerMap$ServerCell"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(
                Advice.to(CellScope.class).on(named("Unload").and(hasDescriptor("()V")))))
            .type(named("zombie.iso.IsoChunk"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(
                Advice.to(ChunkCount.class).on(named("removeFromWorld").and(hasDescriptor("()V")))));
        if (!SEARCH) return builder;
        return builder.type(named("zombie.entity.util.Array"))
            .transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Search.class).on(named("removeValue").and(hasDescriptor("(Ljava/lang/Object;Z)Z"))))
                .visit(Advice.to(MatchIndex.class).on(named("removeIndex").and(hasDescriptor("(I)Ljava/lang/Object;")))));
    }

    static void start() {
        if (!ENABLED) return;
        var setup = new Recording();
        try {
            setup.setName("Unload sampling");
            setup.setMaxSize(128L * 1024 * 1024);
            setup.enable(EVENT);
            setup.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
            setup.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2));
            setup.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1)).withStackTrace();
            setup.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1)).withStackTrace();
            setup.enable("jdk.ThreadSleep").withThreshold(Duration.ofMillis(1)).withStackTrace();
            setup.enable("jdk.GCPhasePause").withoutStackTrace();
            setup.start();
            recording = setup;
        } catch (RuntimeException | Error failure) {
            setup.close();
            throw failure;
        }
    }

    public static void beginPostupdate() {
        if (recording == null || !StreamingProbe.onStreamingOwner()) return;
        window = new UnloadPostupdate();
        window.cycle = StreamingProbe.activeCycle();
        window.postupdate = ++ordinal;
        window.searchEnabled = SEARCH;
        startCpu = cpu();
        window.begin();
    }

    public static void endPostupdate() {
        if (window == null || !StreamingProbe.onStreamingOwner()) return;
        window.end();
        if (window.cells > 0) {
            long stopCpu = cpu();
            window.ownerCpuNanos = startCpu < 0 || stopCpu < 0 ? -1 : stopCpu - startCpu;
            window.commit();
        }
        window = null;
    }

    static void finish(Path directory) throws IOException {
        if (recording == null) return;
        try (var completed = recording) {
            recording = null;
            completed.stop();
            completed.dump(directory.resolve(RECORDING));
        }
    }

    static void discard() {
        if (recording != null) {
            recording.close();
            recording = null;
        }
    }

    private static long cpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
            ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    public static boolean beginCell() {
        if (window == null || !StreamingProbe.onStreamingOwner()) return false;
        window.cells++;
        cellDepth++;
        return true;
    }

    public static void endCell(boolean armed) {
        if (armed) cellDepth--;
    }

    static boolean inCell() { return cellDepth != 0 && StreamingProbe.onStreamingOwner(); }

    public static void countChunk() {
        if (cellDepth != 0 && StreamingProbe.onStreamingOwner()) window.chunks++;
    }

    public static class CellScope {
        @Advice.OnMethodEnter public static boolean enter() { return beginCell(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean armed) { endCell(armed); }
    }

    public static class ChunkCount {
        @Advice.OnMethodEnter public static void enter() { countChunk(); }
    }

    public static class Search {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Argument(1) boolean identity) { return beginSearch(identity); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started, @Advice.FieldValue("size") int size,
                                @Advice.Return boolean found, @Advice.Thrown Throwable failure) {
            endSearch(started, size, found, failure);
        }
    }

    public static class MatchIndex {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) int index) { matched(index); }
    }

    // Identity comparisons in native Array.removeValue cannot invoke a nested equals callback.
    private static boolean searching;
    private static int match;

    public static long beginSearch(boolean identity) {
        if (!identity || cellDepth == 0 || !StreamingProbe.onStreamingOwner()) return 0;
        searching = true;
        match = -1;
        return System.nanoTime();
    }

    public static void matched(int index) {
        if (searching && StreamingProbe.onStreamingOwner()) match = index;
    }

    public static void endSearch(long started, int size, boolean found, Throwable failure) {
        if (started == 0) return;
        long elapsed = System.nanoTime() - started;
        window.searchNanos += elapsed;
        searching = false;
        window.searchCalls++;
        if (failure != null || found && match < 0) {
            window.searchIncomplete++;
        } else {
            long candidates = found ? match + 1L : size;
            window.searchCandidates += candidates;
            BucketUnloadProbe.nativeSearch(candidates, elapsed);
        }
    }
}
