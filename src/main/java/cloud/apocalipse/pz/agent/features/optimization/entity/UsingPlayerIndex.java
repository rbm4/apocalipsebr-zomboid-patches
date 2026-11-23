package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import zombie.characters.IsoPlayer;
import zombie.entity.EntityBucket;
import zombie.entity.GameEntity;
import zombie.entity.util.Array;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** Live successors preserve vanilla's positional traversal through callbacks and swap removals. */
public final class UsingPlayerIndex {
    private static final IdentityHashMap<EntityBucket, UsingPlayerIndex> BUCKETS = new IdentityHashMap<>();
    private static final IdentityHashMap<GameEntity, ArrayList<UsingPlayerIndex>> OWNERS = new IdentityHashMap<>();
    private static final ClassValue<Boolean> PLAIN_ACCESSORS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getUsingPlayer").getDeclaringClass() == GameEntity.class
                    && type.getMethod("isValidEngineEntity").getDeclaringClass() == GameEntity.class;
            } catch (ReflectiveOperationException | SecurityException failure) {
                return false;
            }
        }
    };

    private final IdentityHashMap<GameEntity, Slot> slots = new IdentityHashMap<>();
    private final TreeMap<Integer, Slot> selected = new TreeMap<>();

    private UsingPlayerIndex(EntityBucket bucket) {
        var entities = bucket.getEntities();
        for (int position = 0; position < entities.size(); position++) {
            add(entities.get(position), position);
        }
    }

    public static boolean update(EntityBucket bucket) {
        if (!GameServer.server || GameClient.client) {
            return false;
        }
        var index = BUCKETS.computeIfAbsent(bucket, key -> {
            BucketRemovalIndex.release(key);
            return new UsingPlayerIndex(key);
        });
        int position = -1;
        int visited = 0;
        try {
            for (var next = index.selected.higherEntry(position); next != null; next = index.selected.higherEntry(position)) {
                position = next.getKey();
                visited++;
                updatePlayer(next.getValue().entity);
            }
        } finally {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_USING_PLAYER_CANDIDATES, visited);
        }
        return true;
    }

    private static void updatePlayer(GameEntity entity) {
        if (entity.isValidEngineEntity()) {
            IsoPlayer player = entity.getUsingPlayer();
            if (entity.getUsingPlayer() != null && (player.getX() < entity.getX() - 10.0F
                    || player.getX() > entity.getX() + 10.0F || player.getY() < entity.getY() - 10.0F
                    || player.getY() > entity.getY() + 10.0F || player.getZ() != entity.getZ() || player.isDead())) {
                entity.setUsingPlayer(null);
            }
        }
    }

    static void appended(EntityBucket bucket, GameEntity entity, int position) {
        var index = BUCKETS.get(bucket);
        if (index != null) {
            index.add(entity, position);
        }
    }

    static int removing(EntityBucket bucket, Array<GameEntity> entries, GameEntity entity, boolean identity) {
        var index = BUCKETS.get(bucket);
        if (index == null) {
            return -1;
        }
        Slot removed = index.slots.get(entity);
        Slot tail = entries.size == 0 ? null : index.slots.get(entries.peek());
        if (!identity || removed == null || tail == null || removed.position >= entries.size
                || entries.get(removed.position) != entity) {
            index.slots.keySet().forEach(index::detach);
            BUCKETS.remove(bucket);
            return -1;
        }

        index.slots.remove(entity);
        index.selected.remove(removed.position);
        index.detach(entity);
        if (tail != removed) {
            index.selected.remove(tail.position);
            tail.position = removed.position;
            index.refresh(tail);
        }
        return removed.position;
    }

    public static void changed(Object instance) {
        GameEntity entity = (GameEntity) instance;
        var indexes = OWNERS.get(entity);
        if (indexes != null) {
            for (UsingPlayerIndex index : indexes) {
                index.refresh(index.slots.get(entity));
            }
        }
    }

    static void reset() {
        BUCKETS.clear();
        OWNERS.clear();
    }

    private void add(GameEntity entity, int position) {
        Slot slot = new Slot(entity, position);
        slots.put(entity, slot);
        OWNERS.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(this);
        refresh(slot);
    }

    private void detach(GameEntity entity) {
        var owners = OWNERS.get(entity);
        owners.remove(this);
        if (owners.isEmpty()) {
            OWNERS.remove(entity);
        }
    }

    private void refresh(Slot slot) {
        if (!PLAIN_ACCESSORS.get(slot.entity.getClass()) || slot.entity.getUsingPlayer() != null) {
            selected.put(slot.position, slot);
        } else {
            selected.remove(slot.position);
        }
    }

    private static final class Slot {
        final GameEntity entity;
        int position;

        Slot(GameEntity entity, int position) {
            this.entity = entity;
            this.position = position;
        }
    }
}
