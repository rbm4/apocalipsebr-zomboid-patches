package zombie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

public final class ApocBRServerTelemetryLite {
    private static final long INTERVAL_MILLIS = Math.max(5000L, Long.getLong("apocbr.telemetry.intervalMs", 30000L));
    private static final boolean LUA_ENABLED = Boolean.getBoolean("apocbr.telemetry.lua.enabled");
    private static final Path OUTPUT_PATH = Path.of(System.getProperty("apocbr.telemetry.path", "apocbr-telemetry.ndjson"));
    private static final ArrayBlockingQueue<String> outputQueue = new ArrayBlockingQueue<>(Math.max(1, Integer.getInteger("apocbr.telemetry.queueCapacity", 64)));
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicLong sequence = new AtomicLong();
    private static final LongAdder dropped = new LongAdder();
    private static final LongAdder tickCount = new LongAdder();
    private static final LongAdder tickNanos = new LongAdder();
    private static final AtomicLong tickMaxNanos = new AtomicLong();
    private static final ConcurrentHashMap<String, LuaTiming> luaEvents = new ConcurrentHashMap<>();
    private static volatile long intervalStartedMillis = System.currentTimeMillis();
    private static volatile long nextOutputMillis = intervalStartedMillis + INTERVAL_MILLIS;

    private ApocBRServerTelemetryLite() {
    }

    public static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Thread writer = new Thread(ApocBRServerTelemetryLite::writeLoop, "ApocBR-Telemetry-Writer");
        writer.setDaemon(true);
        writer.start();
    }

    public static boolean isLuaEnabled() {
        return LUA_ENABLED;
    }

    public static void recordTick(long nanos) {
        if (nanos < 0L) {
            return;
        }

        tickCount.increment();
        tickNanos.add(nanos);
        tickMaxNanos.accumulateAndGet(nanos, Math::max);
        long now = System.currentTimeMillis();
        if (now >= nextOutputMillis) {
            emit(now);
        }
    }

    public static void recordLuaEvent(String eventName, int callbackCount, long nanos) {
        if (!LUA_ENABLED || eventName == null || nanos < 0L) {
            return;
        }

        luaEvents.computeIfAbsent(eventName, ignored -> new LuaTiming()).record(callbackCount, nanos);
    }

    private static synchronized void emit(long now) {
        if (now < nextOutputMillis) {
            return;
        }

        long ticks = tickCount.sumThenReset();
        long nanos = tickNanos.sumThenReset();
        long maxNanos = tickMaxNanos.getAndSet(0L);
        long elapsedMillis = Math.max(1L, now - intervalStartedMillis);
        int players = Math.max(GameServer.IDToPlayerMap.size(), GameServer.Players.size());
        StringBuilder json = new StringBuilder(512);
        json.append("{\"schemaVersion\":1");
        json.append(",\"seq\":").append(sequence.incrementAndGet());
        json.append(",\"ts\":").append(now);
        json.append(",\"tick\":{\"count\":").append(ticks);
        json.append(",\"rate\":").append(decimal(ticks * 1000.0 / elapsedMillis));
        json.append(",\"avgMs\":").append(decimal(ticks == 0L ? 0.0 : nanos / 1000000.0 / ticks));
        json.append(",\"maxMs\":").append(decimal(maxNanos / 1000000.0)).append('}');
        json.append(",\"playersOnline\":").append(players);
        json.append(",\"dropped\":").append(dropped.sum());
        json.append(",\"lua\":{\"enabled\":").append(LUA_ENABLED);
        if (LUA_ENABLED) {
            json.append(",\"events\":[");
            ArrayList<Map.Entry<String, LuaTiming>> entries = new ArrayList<>(luaEvents.entrySet());
            entries.sort(Comparator.comparingLong((Map.Entry<String, LuaTiming> entry) -> entry.getValue().nanos.sum()).reversed());
            boolean first = true;
            for (Map.Entry<String, LuaTiming> entry : entries) {
                LuaSnapshot snapshot = entry.getValue().snapshotAndReset();
                if (snapshot.calls == 0L) {
                    continue;
                }

                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"name\":\"").append(escape(entry.getKey())).append("\"");
                json.append(",\"calls\":").append(snapshot.calls);
                json.append(",\"callbacks\":").append(snapshot.callbacks);
                json.append(",\"avgMs\":").append(decimal(snapshot.nanos / 1000000.0 / snapshot.calls));
                json.append(",\"maxMs\":").append(decimal(snapshot.maxNanos / 1000000.0)).append('}');
            }
            json.append(']');
        }
        json.append("}}\n");
        if (!outputQueue.offer(json.toString())) {
            dropped.increment();
        }

        intervalStartedMillis = now;
        nextOutputMillis = now + INTERVAL_MILLIS;
    }

    private static void writeLoop() {
        while (true) {
            try {
                String line = outputQueue.poll(1L, TimeUnit.SECONDS);
                if (line != null) {
                    Files.writeString(
                        OUTPUT_PATH,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                DebugLog.log("[ApocBR][Telemetry] failed to append " + OUTPUT_PATH + ": " + exception.getMessage());
            }
        }
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class LuaTiming {
        final LongAdder calls = new LongAdder();
        final LongAdder callbacks = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();

        void record(int callbackCount, long durationNanos) {
            this.calls.increment();
            this.callbacks.add(Math.max(0, callbackCount));
            this.nanos.add(durationNanos);
            this.maxNanos.accumulateAndGet(durationNanos, Math::max);
        }

        LuaSnapshot snapshotAndReset() {
            return new LuaSnapshot(this.calls.sumThenReset(), this.callbacks.sumThenReset(), this.nanos.sumThenReset(), this.maxNanos.getAndSet(0L));
        }
    }

    private static final class LuaSnapshot {
        final long calls;
        final long callbacks;
        final long nanos;
        final long maxNanos;

        LuaSnapshot(long calls, long callbacks, long nanos, long maxNanos) {
            this.calls = calls;
            this.callbacks = callbacks;
            this.nanos = nanos;
            this.maxNanos = maxNanos;
        }
    }
}
