package zombie;

/** Structural double for the legacy timing arguments; never packaged. */
public final class ApocBRServerTelemetryLite {
    public static long tickCalls;
    public static long tickNanos;
    public static long loopCalls;
    public static long loopNanos;

    private ApocBRServerTelemetryLite() {
    }

    public static void recordTick(long durationNanos) {
        tickCalls++;
        tickNanos += durationNanos;
    }

    public static void recordLoop(long durationNanos) {
        loopCalls++;
        loopNanos += durationNanos;
    }

    public static void reset() {
        tickCalls = 0L;
        tickNanos = 0L;
        loopCalls = 0L;
        loopNanos = 0L;
    }
}
