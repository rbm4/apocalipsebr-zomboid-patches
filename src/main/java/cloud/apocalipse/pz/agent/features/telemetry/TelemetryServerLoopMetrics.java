package cloud.apocalipse.pz.agent.features.telemetry;

/** Bridges validated GameServer loop bytecode anchors to detached telemetry aggregates. */
public final class TelemetryServerLoopMetrics {
    private static final ThreadLocal<Iteration> CURRENT = ThreadLocal.withInitial(Iteration::new);
    private static volatile boolean enabled;
    private static volatile boolean legacyTickAnchor;
    private static volatile boolean legacyLoopAnchor;

    private TelemetryServerLoopMetrics() {
    }

    public static void configure(boolean available, boolean hasLegacyTick, boolean hasLegacyLoop) {
        legacyTickAnchor = hasLegacyTick;
        legacyLoopAnchor = hasLegacyLoop;
        enabled = available;
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean hasLegacyTickAnchor() {
        return legacyTickAnchor;
    }

    static boolean hasLegacyLoopAnchor() {
        return legacyLoopAnchor;
    }

    public static void beginIteration(long startedAtNanos) {
        if (!enabled || (legacyTickAnchor && legacyLoopAnchor)) {
            return;
        }

        Iteration iteration = CURRENT.get();
        iteration.startedAtNanos = startedAtNanos;
        iteration.active = true;
        TickDiagnostics.begin(startedAtNanos);
    }

    public static void recordVanillaTick() {
        if (!enabled || legacyTickAnchor) {
            return;
        }

        long endedAtNanos = System.nanoTime();
        Iteration iteration = CURRENT.get();
        if (iteration.active) {
            long nanos = endedAtNanos - iteration.startedAtNanos;
            TelemetryRegistry.recordTick(nanos);
            TickDiagnostics.recordTick(nanos);
        }
    }

    public static void recordLegacyTick(long durationNanos) {
        if (!enabled || !legacyTickAnchor) {
            return;
        }

        TelemetryRegistry.recordTick(durationNanos);
    }

    public static void finishVanillaIteration() {
        if (!enabled || legacyLoopAnchor) {
            return;
        }

        long endedAtNanos = System.nanoTime();
        Iteration iteration = CURRENT.get();
        if (iteration.active) {
            finish(iteration, endedAtNanos - iteration.startedAtNanos);
        }
    }

    public static void finishLegacyIteration(long durationNanos) {
        if (!enabled || !legacyLoopAnchor) {
            return;
        }

        if (!(legacyTickAnchor && legacyLoopAnchor)) {
            CURRENT.get().active = false;
            TickDiagnostics.end();
        }
        TelemetryRegistry.recordLoop(durationNanos);
        TelemetrySnapshotReporter.onServerLoop();
    }

    private static void finish(Iteration iteration, long durationNanos) {
        iteration.active = false;
        TickDiagnostics.end();
        TelemetryRegistry.recordLoop(durationNanos);
        TelemetrySnapshotReporter.onServerLoop();
    }

    private static final class Iteration {
        private long startedAtNanos;
        private boolean active;
    }
}
