package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import se.krka.kahlua.vm.LuaClosure;
import zombie.ZomboidFileSystem;

/** Main-loop evidence. Capture only detached values and bound callback cardinality per reporting window. */
public final class TickDiagnostics {
    private static final long SLOW_TICK_NANOS = 100_000_000L;
    private static final long CALLBACK_THRESHOLD_NANOS = 1_000_000L;
    private static final int MAX_CALLBACK_KEYS = 256;
    private static final int MAX_FRAME_CALLBACKS = 8;
    private static final int MAX_WINDOW_CALLBACKS = 12;
    private static final OptimizationTelemetry.Phase[] PHASES = OptimizationTelemetry.Phase.values();
    private static final long[] FRAME_PHASES = new long[PHASES.length];
    private static final Map<CallbackKey, CallbackStats> FRAME_CALLBACKS = new HashMap<>();
    private static final Map<CallbackKey, CallbackStats> WINDOW_CALLBACKS = new HashMap<>();
    private static final Map<String, Source> SOURCES = new HashMap<>();

    private static volatile Thread owner;
    private static boolean active;
    private static String event;
    private static int eventDepth;
    private static int callbackDepth;
    private static long startedAt;
    private static long frameLuaNanos;
    private static long frameLuaCalls;
    private static long omittedCalls;
    private static Peak peak;

    private TickDiagnostics() {}

    static void begin(long started) {
        if (owner != Thread.currentThread()) owner = Thread.currentThread();
        active = true;
        startedAt = started;
        event = null;
        eventDepth = 0;
        callbackDepth = -1;
        frameLuaNanos = 0;
        frameLuaCalls = 0;
        Arrays.fill(FRAME_PHASES, 0);
        FRAME_CALLBACKS.clear();
    }

    public static boolean activeOnCurrentThread() {
        return owner == Thread.currentThread() && active;
    }

    static void end() { active = false; }

    static void recordPhase(OptimizationTelemetry.Phase phase, long nanos) {
        if (activeOnCurrentThread()) FRAME_PHASES[phase.ordinal()] += nanos;
    }

    public static void recordIncoming() {
        if (activeOnCurrentThread()) OptimizationTelemetry.Phase.INCOMING.recordSince(startedAt);
    }

    static void recordTick(long nanos) {
        if (nanos <= SLOW_TICK_NANOS || (peak != null && nanos <= peak.nanos())) return;
        Map<String, Double> phases = new LinkedHashMap<>();
        for (var phase : PHASES) {
            long duration = FRAME_PHASES[phase.ordinal()];
            if (duration != 0) phases.put(String.join(".", phase.path()), milliseconds(duration));
        }
        long timestamp = System.currentTimeMillis();
        List<Map<String, Object>> callbacks = ranked(FRAME_CALLBACKS, MAX_FRAME_CALLBACKS);
        peak = new Peak(nanos, timestamp, phases, frameLuaCalls, milliseconds(frameLuaNanos), callbacks);
    }

    public static String enterEvent(String name) {
        if (!activeOnCurrentThread()) return null;
        String previous = event;
        eventDepth++;
        event = name;
        return previous;
    }

    public static void exitEvent(String previous) {
        if (!activeOnCurrentThread()) return;
        eventDepth--;
        event = previous;
    }

    public static boolean canTimeCallback() {
        return activeOnCurrentThread() && eventDepth != callbackDepth;
    }

    public static int enterCallback() {
        int previous = callbackDepth;
        callbackDepth = eventDepth;
        return previous;
    }

    public static void recordCallback(LuaClosure closure, int previousDepth, long nanos) {
        callbackDepth = previousDepth;
        frameLuaCalls++;
        if (previousDepth < 0) frameLuaNanos += nanos;
        if (nanos < CALLBACK_THRESHOLD_NANOS) return;

        var prototype = closure.prototype;
        String filename = prototype.filename;
        if (filename == null || filename.isBlank()) filename = prototype.file;
        Source source = resolveSource(filename);
        String function = closure.debugName == null ? prototype.name : closure.debugName;
        int line = prototype.lines == null || prototype.lines.length == 0 ? 0 : prototype.lines[0];
        CallbackKey key = new CallbackKey(limit(event, "(direct)", 96), source,
            limit(function, "(anonymous)", 96), line);
        if (!accumulate(WINDOW_CALLBACKS, key, nanos)) omittedCalls++;
        accumulate(FRAME_CALLBACKS, key, nanos);
    }

    private static boolean accumulate(Map<CallbackKey, CallbackStats> table, CallbackKey key, long nanos) {
        CallbackStats stats = table.get(key);
        if (stats == null) {
            if (table.size() >= MAX_CALLBACK_KEYS) return false;
            stats = new CallbackStats();
            table.put(key, stats);
        }
        stats.record(nanos);
        return true;
    }

    private static Source resolveSource(String filename) {
        if (filename == null || filename.isBlank()) return new Source(null, null, "(unknown)");
        Source known = SOURCES.get(filename);
        if (known != null) return known;

        String path = filename.replace('\\', '/');
        String mod = null;
        try {
            ZomboidFileSystem files = ZomboidFileSystem.instance;
            path = files.getString(path).replace('\\', '/');
            String lower = path.toLowerCase(Locale.ROOT);
            int longest = -1;
            for (String id : files.getModIDs()) {
                String directory = files.getModDir(id);
                if (directory == null) continue;
                String prefix = directory.replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!prefix.endsWith("/")) prefix += "/";
                if (prefix.length() > longest && lower.startsWith(prefix)) {
                    mod = id;
                    longest = prefix.length();
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // The original Lua source still identifies the callback when the file map is unavailable.
        }
        String lower = path.toLowerCase(Locale.ROOT);
        String workshopId = null;
        String workshopPrefix = "/workshop/content/108600/";
        int workshop = lower.indexOf(workshopPrefix);
        if (workshop >= 0) {
            int start = workshop + workshopPrefix.length();
            int end = lower.indexOf('/', start);
            if (end > start && end - start <= 20) workshopId = path.substring(start, end);
        }
        int media = lower.lastIndexOf("media/lua/");
        String file = media >= 0 ? path.substring(media) : path.substring(path.lastIndexOf('/') + 1);
        Source source = new Source(mod == null ? null : limit(mod, "", 96), workshopId, limit(file, "(unknown)", 192));
        if (SOURCES.size() < MAX_CALLBACK_KEYS) SOURCES.put(filename, source);
        return source;
    }

    static Report snapshotAndReset() {
        Report report = new Report(ranked(WINDOW_CALLBACKS, MAX_WINDOW_CALLBACKS), omittedCalls, peak);
        WINDOW_CALLBACKS.clear();
        SOURCES.clear();
        omittedCalls = 0;
        peak = null;
        return report;
    }

    private static List<Map<String, Object>> ranked(Map<CallbackKey, CallbackStats> table, int limit) {
        return table.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<CallbackKey, CallbackStats> entry) -> entry.getValue().total)
                .reversed()).limit(limit)
            .map(entry -> {
                var values = entry.getValue();
                return sample(entry.getKey(), values.calls, values.total, values.max);
            }).toList();
    }

    private static Map<String, Object> sample(CallbackKey key, long calls, long total, long max) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("hook", key.hook());
        if (key.source().modId() != null) item.put("modId", key.source().modId());
        if (key.source().workshopId() != null) item.put("workshopId", key.source().workshopId());
        item.put("file", key.source().file());
        item.put("function", key.function());
        item.put("line", key.line());
        item.put("calls", calls);
        item.put("totalMs", milliseconds(total));
        item.put("maxMs", milliseconds(max));
        return item;
    }

    private static double milliseconds(long nanos) { return Math.round(nanos / 1_000.0) / 1_000.0; }

    private static String limit(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        return value.substring(0, Math.min(value.length(), max));
    }

    record Report(List<Map<String, Object>> callbacks, long omittedCalls, Peak peak) {
        static final Report EMPTY = new Report(List.of(), 0, null);
    }

    record Peak(long nanos, long ts, Map<String, Double> phases, long luaCalls, double luaMs,
                List<Map<String, Object>> callbacks) {}

    private record Source(String modId, String workshopId, String file) {}
    private record CallbackKey(String hook, Source source, String function, int line) {}
    private static final class CallbackStats {
        long calls;
        long total;
        long max;

        void record(long nanos) {
            calls++;
            total += nanos;
            max = Math.max(max, nanos);
        }
    }
}
