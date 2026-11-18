package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Small game-independent accumulator; export and scheduling can be added later. */
public final class TelemetryRegistry {
    private static final Accumulator LUA_EVENT_TRIGGER = new Accumulator();

    private TelemetryRegistry() {
    }

    /** Public because inlined advice calls this helper from the transformed game package. */
    public static void recordLuaEventTrigger(long durationNanos) {
        LUA_EVENT_TRIGGER.record(Math.max(0L, durationNanos));
    }

    public static Map<String, MetricSnapshot> snapshot() {
        return Map.of(TelemetryModule.LUA_EVENT_TRIGGER_DURATION, LUA_EVENT_TRIGGER.snapshot());
    }

    public record MetricSnapshot(long calls, long totalNanos, long maxNanos) {
    }

    private static final class Accumulator {
        private final LongAdder calls = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long durationNanos) {
            this.calls.increment();
            this.totalNanos.add(durationNanos);
            this.maxNanos.accumulateAndGet(durationNanos, Math::max);
        }

        MetricSnapshot snapshot() {
            return new MetricSnapshot(this.calls.sum(), this.totalNanos.sum(), this.maxNanos.get());
        }
    }
}
