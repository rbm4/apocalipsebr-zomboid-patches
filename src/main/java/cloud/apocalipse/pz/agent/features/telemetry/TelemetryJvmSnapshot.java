package cloud.apocalipse.pz.agent.features.telemetry;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TelemetryJvmSnapshot {
    private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final long[] PREVIOUS_COUNTS = new long[COLLECTORS.size()];
    private static final long[] PREVIOUS_MILLIS = new long[COLLECTORS.size()];

    private TelemetryJvmSnapshot() {}

    static void baseline() {
        for (int i = 0; i < COLLECTORS.size(); i++) {
            PREVIOUS_COUNTS[i] = COLLECTORS.get(i).getCollectionCount();
            PREVIOUS_MILLIS[i] = COLLECTORS.get(i).getCollectionTime();
        }
    }

    static Map<String, Object> capture() {
        List<Map<String, Object>> collectors = new ArrayList<>();
        long totalCount = 0;
        long totalMillis = 0;
        for (int i = 0; i < COLLECTORS.size(); i++) {
            var collector = COLLECTORS.get(i);
            long count = collector.getCollectionCount();
            long millis = collector.getCollectionTime();
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("name", collector.getName());
            if (count >= 0 && PREVIOUS_COUNTS[i] >= 0) {
                long delta = Math.max(0, count - PREVIOUS_COUNTS[i]);
                totalCount += delta;
                sample.put("count", delta);
            }
            if (millis >= 0 && PREVIOUS_MILLIS[i] >= 0) {
                long delta = Math.max(0, millis - PREVIOUS_MILLIS[i]);
                totalMillis += delta;
                sample.put("totalMs", delta);
            }
            PREVIOUS_COUNTS[i] = count;
            PREVIOUS_MILLIS[i] = millis;
            collectors.add(sample);
        }
        Runtime runtime = Runtime.getRuntime();
        long committed = runtime.totalMemory();
        return Map.of("heapUsedBytes", committed - runtime.freeMemory(),
            "heapCommittedBytes", committed, "heapMaxBytes", runtime.maxMemory(),
            "gcCount", totalCount, "gcMs", totalMillis, "collectors", collectors);
    }
}
