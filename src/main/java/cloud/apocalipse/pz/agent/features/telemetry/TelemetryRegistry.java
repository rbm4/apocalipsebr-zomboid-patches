package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Aggregates detached Lua event measurements between telemetry snapshots. */
public final class TelemetryRegistry {
    private static final int MAX_LUA_EVENT_NAMES = 128;
    private static final int MAX_EVENT_NAME_LENGTH = 128;
    private static final String OTHER_EVENT_NAME = "(other)";
    private static final ConcurrentHashMap<String, Accumulator> LUA_EVENTS = new ConcurrentHashMap<>();
    private static final LongAdder TICK_CALLS = new LongAdder();
    private static final LongAdder TICK_NANOS = new LongAdder();
    private static final LongAdder OVER_BUDGET_TICKS = new LongAdder();
    private static final AtomicLong TICK_MAX_NANOS = new AtomicLong();
    private static final LongAdder LOOP_CALLS = new LongAdder();
    private static final LongAdder LOOP_NANOS = new LongAdder();

    private TelemetryRegistry() {
    }

    /** Public because inlined Advice calls this helper from the transformed game package. */
    public static void recordLuaEvent(String eventName, int callbackCount, long durationNanos) {
        String name = eventName == null || eventName.isBlank() ? "(unnamed)" : eventName;
        if (name.length() > MAX_EVENT_NAME_LENGTH) {
            name = name.substring(0, MAX_EVENT_NAME_LENGTH);
        }
        accumulator(name).record(Math.max(0, callbackCount), Math.max(0L, durationNanos));
    }

    public static void recordTick(long durationNanos) {
        if (durationNanos < 0L) {
            return;
        }

        TICK_CALLS.increment();
        TICK_NANOS.add(durationNanos);
        TICK_MAX_NANOS.accumulateAndGet(durationNanos, Math::max);
        if (durationNanos > 100_000_000L) {
            OVER_BUDGET_TICKS.increment();
        }
    }

    public static TickSnapshot snapshotTicksAndReset() {
        return new TickSnapshot(
            TICK_CALLS.sumThenReset(),
            TICK_NANOS.sumThenReset(),
            TICK_MAX_NANOS.getAndSet(0L),
            OVER_BUDGET_TICKS.sumThenReset()
        );
    }

    public static void recordLoop(long durationNanos) {
        if (durationNanos < 0L) {
            return;
        }

        LOOP_CALLS.increment();
        LOOP_NANOS.add(durationNanos);
    }

    public static LoopSnapshot snapshotLoopsAndReset() {
        return new LoopSnapshot(LOOP_CALLS.sumThenReset(), LOOP_NANOS.sumThenReset());
    }

    public static Map<String, EventSnapshot> snapshot() {
        return snapshot(false);
    }

    static Map<String, EventSnapshot> snapshotAndReset() {
        return snapshot(true);
    }

    private static Map<String, EventSnapshot> snapshot(boolean reset) {
        TreeMap<String, EventSnapshot> result = new TreeMap<>();
        LUA_EVENTS.forEach((name, accumulator) -> {
            EventSnapshot snapshot = reset ? accumulator.snapshotAndReset() : accumulator.snapshot();
            if (snapshot.calls() > 0) {
                result.put(name, snapshot);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Accumulator accumulator(String name) {
        Accumulator existing = LUA_EVENTS.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (LUA_EVENTS) {
            existing = LUA_EVENTS.get(name);
            if (existing != null) {
                return existing;
            }
            String key = LUA_EVENTS.size() >= MAX_LUA_EVENT_NAMES - 1 ? OTHER_EVENT_NAME : name;
            return LUA_EVENTS.computeIfAbsent(key, ignored -> new Accumulator());
        }
    }

    public record EventSnapshot(long calls, long callbacks, long totalNanos, long maxNanos) {
    }

    public record TickSnapshot(long calls, long totalNanos, long maxNanos, long overBudgetCalls) {
    }

    public record LoopSnapshot(long calls, long totalNanos) {
    }

    private static final class Accumulator {
        private final LongAdder calls = new LongAdder();
        private final LongAdder callbacks = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(int callbackCount, long durationNanos) {
            this.calls.increment();
            this.callbacks.add(callbackCount);
            this.totalNanos.add(durationNanos);
            this.maxNanos.accumulateAndGet(durationNanos, Math::max);
        }

        EventSnapshot snapshot() {
            return new EventSnapshot(
                this.calls.sum(),
                this.callbacks.sum(),
                this.totalNanos.sum(),
                this.maxNanos.get()
            );
        }

        EventSnapshot snapshotAndReset() {
            return new EventSnapshot(
                this.calls.sumThenReset(),
                this.callbacks.sumThenReset(),
                this.totalNanos.sumThenReset(),
                this.maxNanos.getAndSet(0L)
            );
        }
    }
}
