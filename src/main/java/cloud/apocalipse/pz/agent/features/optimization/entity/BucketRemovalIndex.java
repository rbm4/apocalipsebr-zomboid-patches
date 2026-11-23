package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import gnu.trove.map.custom_hash.TObjectIntCustomHashMap;
import gnu.trove.strategy.HashingStrategy;
import java.util.IdentityHashMap;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.util.Array;
import zombie.network.GameServer;

/** Owner-side positions for buckets whose existing simulation indexes cannot serve removal. */
final class BucketRemovalIndex {
    private static final int ACTIVATE_SIZE = 256;
    private static final int RELEASE_SIZE = ACTIVATE_SIZE / 2;
    private static final boolean ENABLED =
        AgentConfiguration.entityBucketRemovalIndexEnabled();
    private static final IdentityHashMap<EntityBucket, Demand> BUCKETS = new IdentityHashMap<>();

    enum Fallback { DISABLED, UNSUPPORTED, SMALL, FIRST_REMOVAL, INVALID_INDEX, INVALID_SIMULATION_INDEX }

    private enum Identity implements HashingStrategy<GameEntity> {
        INSTANCE;
        @Override public int computeHashCode(GameEntity entity) { return System.identityHashCode(entity); }
        @Override public boolean equals(GameEntity left, GameEntity right) { return left == right; }
    }

    private static final class Demand {
        final Array<GameEntity> entries;
        TObjectIntCustomHashMap<GameEntity> positions;

        Demand(Array<GameEntity> entries) { this.entries = entries; }
    }

    private BucketRemovalIndex() {}

    static boolean remove(Array<GameEntity> entries, GameEntity entity, boolean identity, EntityBucket bucket) {
        if (!ENABLED) return scan(entries, entity, identity, Fallback.DISABLED);
        if (!identity || entries.ordered || !onOwner()) return scan(entries, entity, identity, Fallback.UNSUPPORTED);
        Demand demand = BUCKETS.get(bucket);
        if (demand != null && demand.entries != entries) {
            BUCKETS.remove(bucket);
            return scan(entries, entity, true, Fallback.INVALID_INDEX);
        }

        if (entries.size < RELEASE_SIZE) {
            BUCKETS.remove(bucket);
            return scan(entries, entity, true, Fallback.SMALL);
        }

        if (demand == null || demand.positions == null) {
            if (entries.size < ACTIVATE_SIZE) {
                BUCKETS.remove(bucket);
                return scan(entries, entity, true, Fallback.SMALL);
            }
            if (demand == null) {
                boolean removed = scan(entries, entity, true, Fallback.FIRST_REMOVAL);
                if (removed && entries.size >= ACTIVATE_SIZE) BUCKETS.put(bucket, new Demand(entries));
                return removed;
            }
            demand.positions = build(entries);
        }

        var positions = demand.positions;
        if (positions != null) {
            int position = positions.get(entity);
            var tail = entries.peek();
            if (position >= 0 && position < entries.size && entries.get(position) == entity
                    && positions.get(tail) == entries.size - 1) {
                entries.removeIndex(position);
                if (entries.size < RELEASE_SIZE) {
                    BUCKETS.remove(bucket);
                } else {
                    positions.remove(entity);
                    if (tail != entity) positions.put(tail, position);
                }
                OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_BUCKET_REUSE_POSITION);
                return true;
            }
        }
        BUCKETS.remove(bucket);
        return scan(entries, entity, true, Fallback.INVALID_INDEX);
    }

    static void appended(Array<GameEntity> entries, GameEntity entity, EntityBucket bucket) {
        if (!ENABLED || BUCKETS.isEmpty() || !onOwner()) return;
        var demand = BUCKETS.get(bucket);
        if (demand == null) return;
        if (demand.entries != entries || demand.positions != null
                && (entity == null || demand.positions.put(entity, entries.size - 1) >= 0)) {
            BUCKETS.remove(bucket);
        }
    }

    static void release(EntityBucket bucket) {
        if (ENABLED && !BUCKETS.isEmpty() && onOwner()) BUCKETS.remove(bucket);
    }

    static void reset() { BUCKETS.clear(); }

    private static boolean onOwner() {
        return GameServer.server && Thread.currentThread() == GameServer.mainThread;
    }

    private static TObjectIntCustomHashMap<GameEntity> build(Array<GameEntity> entries) {
        var positions = new TObjectIntCustomHashMap<GameEntity>(Identity.INSTANCE, entries.size, 0.5f, -1);
        for (int index = 0; index < entries.size; index++) {
            GameEntity entity = entries.get(index);
            if (entity == null || positions.put(entity, index) >= 0) return null;
        }
        OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_BUCKET_POSITION_BUILD);
        return positions;
    }

    // The integration observer records the reason at this boundary and counts comparisons in the native loop.
    static boolean scan(Array<GameEntity> entries, GameEntity entity, boolean identity, Fallback reason) {
        return entries.removeValue(entity, identity);
    }
}
