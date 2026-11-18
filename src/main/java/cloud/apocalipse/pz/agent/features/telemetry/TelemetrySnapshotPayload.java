package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            Map<String, TelemetryRegistry.EventSnapshot> luaEvents) {
        if (gameVersion == null || gameVersion.isBlank() || gameVersion.length() > 255
                || intervalMillis < 1 || sequence < 1 || timestampMillis < 1
                || playersOnline < 0 || dropped < 0) {
            throw new IllegalArgumentException("Invalid telemetry snapshot metadata");
        }

        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("count", 0L);
        tick.put("rate", 0.0);
        tick.put("avgMs", 0.0);
        tick.put("maxMs", 0.0);

        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("count", 0L);
        loop.put("totalMs", 0.0);

        List<Map<String, Object>> events = new ArrayList<>(luaEvents.size());
        luaEvents.forEach((name, event) -> {
            if (event.calls() > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("calls", event.calls());
                item.put("callbacks", event.callbacks());
                item.put("totalMs", event.totalNanos() / 1_000_000.0);
                item.put("avgMs", event.totalNanos() / 1_000_000.0 / event.calls());
                item.put("maxMs", event.maxNanos() / 1_000_000.0);
                events.add(item);
            }
        });

        Map<String, Object> lua = new LinkedHashMap<>();
        lua.put("enabled", true);
        lua.put("events", events);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 3);
        snapshot.put("gameVersion", gameVersion);
        snapshot.put("intervalMs", intervalMillis);
        snapshot.put("seq", sequence);
        snapshot.put("ts", timestampMillis);
        snapshot.put("tick", tick);
        snapshot.put("overBudgetTicks", 0L);
        snapshot.put("loop", loop);
        snapshot.put("phases", Map.of());
        snapshot.put("counters", Map.of());
        snapshot.put("world", Map.of());
        snapshot.put("jvm", Map.of());
        snapshot.put("playersOnline", playersOnline);
        snapshot.put("dropped", dropped);
        snapshot.put("lua", lua);
        String encoded = GSON.toJson(snapshot);
        if (encoded.length() > 65_536) {
            throw new IllegalArgumentException("Telemetry snapshot exceeds the payload limit");
        }
        return encoded;
    }
}
