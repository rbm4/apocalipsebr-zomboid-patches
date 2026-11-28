package cloud.apocalipse.pz.agent.features.optimization.los.perception;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import gnu.trove.map.hash.TLongObjectHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Owner-thread storage used by the existing dedicated LOS hook. */
public final class PerceptionBuckets<E> {
    private static final boolean ENABLED =
        AgentConfiguration.optimizationEnabled(AgentConfiguration.PERCEPTION_BUCKETS_OPTIMIZATION);

    private final TLongObjectHashMap<ArrayList<E>> primitive = ENABLED ? new TLongObjectHashMap<>() : null;
    private final HashMap<Long, ArrayList<E>> boxed = ENABLED ? null : new HashMap<>();

    public void add(long key, E candidate) {
        if (!ENABLED) {
            boxed.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
            return;
        }
        ArrayList<E> bucket = primitive.get(key);
        if (bucket == null) {
            bucket = new ArrayList<>();
            primitive.put(key, bucket);
        }
        bucket.add(candidate);
    }

    public void append(long key, List<E> destination) {
        ArrayList<E> bucket = ENABLED ? primitive.get(key) : boxed.get(key);
        if (bucket == null) return;
        if (!ENABLED) {
            destination.addAll(bucket);
            return;
        }
        for (int i = 0, size = bucket.size(); i < size; i++) {
            destination.add(bucket.get(i));
        }
    }

    public void remove(long key, E candidate) {
        ArrayList<E> bucket = ENABLED ? primitive.get(key) : boxed.get(key);
        if (bucket == null || !bucket.remove(candidate) || !bucket.isEmpty()) return;
        if (ENABLED) primitive.remove(key);
        else boxed.remove(key);
    }

    public void clear() {
        if (ENABLED) primitive.clear();
        else boxed.clear();
    }
}

