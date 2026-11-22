package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

final class TelemetrySnapshotPayload {
    private static final Gson GSON = new Gson();

    private TelemetrySnapshotPayload() {
    }

    static String encode(
            String gameVersion,
            long intervalMillis,
            long sequence,
            long timestampMillis,
            int playersOnline,
            long dropped,
            TelemetryRegistry.TickSnapshot ticks,
            TelemetryRegistry.LoopSnapshot loops,
            Map<String, Object> world,
            Map<String, TelemetryRegistry.EventSnapshot> luaEvents,
            OptimizationTelemetry.Snapshot optimizations,
            Map<String, Object> jvm,
            TickDiagnostics.Report diagnostics,
            boolean callbackTiming) {
        if (gameVersion == null || gameVersion.isBlank() || gameVersion.length() > 255
                || intervalMillis < 1 || sequence < 1 || timestampMillis < 1
                || playersOnline < 0 || dropped < 0 || ticks == null
                || ticks.calls() < 0 || ticks.totalNanos() < 0 || ticks.maxNanos() < 0
                || ticks.overBudgetCalls() < 0 || loops == null
                || loops.calls() < 0 || loops.totalNanos() < 0) {
            throw new IllegalArgumentException("Invalid telemetry snapshot metadata");
        }

        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("count", ticks.calls());
        tick.put("rate", decimal(ticks.calls() * 1000.0 / intervalMillis));
        tick.put("avgMs", decimal(ticks.calls() == 0L
            ? 0.0
            : ticks.totalNanos() / 1_000_000.0 / ticks.calls()));
        tick.put("maxMs", decimal(ticks.maxNanos() / 1_000_000.0));
        if (diagnostics.peak() != null) {
            var peak = diagnostics.peak();
            Map<String, Object> slowest = new LinkedHashMap<>();
            slowest.put("ts", peak.ts());
            slowest.put("totalMs", decimal(peak.nanos() / 1_000_000.0));
            slowest.put("phaseMs", peak.phases());
            if (callbackTiming) {
                slowest.put("luaCalls", peak.luaCalls());
                slowest.put("luaMs", peak.luaMs());
                slowest.put("callbacks", peak.callbacks());
            }
            tick.put("slowest", slowest);
        }

        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("count", loops.calls());
        loop.put("totalMs", decimal(loops.totalNanos() / 1_000_000.0));

        List<Map<String, Object>> events = new ArrayList<>(luaEvents.size());
        luaEvents.forEach((name, event) -> {
            if (event.calls() > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("calls", event.calls());
                item.put("callbacks", event.callbacks());
                item.put("totalMs", decimal(event.totalNanos() / 1_000_000.0));
                item.put("avgMs", decimal(event.totalNanos() / 1_000_000.0 / event.calls()));
                item.put("maxMs", decimal(event.maxNanos() / 1_000_000.0));
                events.add(item);
            }
        });

        Map<String, Object> lua = new LinkedHashMap<>();
        lua.put("enabled", true);
        lua.put("events", events);
        lua.put("callbackTiming", callbackTiming);
        if (callbackTiming) {
            lua.put("callbacks", diagnostics.callbacks());
            lua.put("callbackCallsOmitted", diagnostics.omittedCalls());
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 3);
        snapshot.put("gameVersion", gameVersion);
        snapshot.put("intervalMs", intervalMillis);
        snapshot.put("seq", sequence);
        snapshot.put("ts", timestampMillis);
        snapshot.put("tick", tick);
        snapshot.put("overBudgetTicks", ticks.overBudgetCalls());
        snapshot.put("loop", loop);
        snapshot.put("phases", phases(optimizations.phases()));
        snapshot.put("counters", optimizations.counters());
        snapshot.put("world", world);
        snapshot.put("jvm", jvm);
        snapshot.put("playersOnline", playersOnline);
        snapshot.put("dropped", dropped);
        snapshot.put("lua", lua);
        String encoded = GSON.toJson(snapshot);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("Telemetry snapshot exceeds the payload limit");
        }
        return encoded;
    }

    private static Map<String, Object> phases(List<OptimizationTelemetry.PhaseSample> samples) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (var sample : samples) {
            Map<String, Object> node = root;
            for (String part : sample.path()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) node.computeIfAbsent(part, ignored -> new LinkedHashMap<>());
                node = child;
            }
            node.put("calls", sample.calls());
            node.put("totalMs", decimal(sample.totalNanos() / 1_000_000.0));
            node.put("avgMs", decimal(sample.totalNanos() / 1_000_000.0 / sample.calls()));
            node.put("maxMs", decimal(sample.maxNanos() / 1_000_000.0));
        }
        return root;
    }

    private static double decimal(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }
}
