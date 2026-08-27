// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import java.util.Comparator;
import zombie.UsedFromLua;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.entity.util.Array;
import zombie.entity.util.BitSet;
import zombie.entity.util.ImmutableArray;
import zombie.entity.util.ObjectSet;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoObject;
import zombie.vehicles.VehiclePart;

@UsedFromLua
public abstract class EntityBucket {
    private final Array<GameEntity> entities;
    private final ImmutableArray<GameEntity> immutableEntities;
    private final Array<EntityBucket.BucketListenerData> listeners = new Array<>(true, 16);
    private final ObjectSet<IBucketListener> listenerSet = new ObjectSet<>();
    private final EntityBucket.BucketListenerComparator listenerComparator = new EntityBucket.BucketListenerComparator();
    private final int index;
    private boolean verbose;
    private boolean needsNullCompaction = true;

    private EntityBucket(int index) {
        this.entities = new Array<>(false, 16);
        this.immutableEntities = new ImmutableArray<>(this.entities);
        this.index = index;
    }

    public final int getIndex() {
        return this.index;
    }

    public final ImmutableArray<GameEntity> getEntities() {
        if (this.needsNullCompaction) {
            this.compactNullEntities();
        }
        return this.immutableEntities;
    }

    public final void setVerbose(boolean b) {
        this.verbose = b;
    }

    protected abstract boolean acceptsEntity(GameEntity arg0);

    final void updateMembership(GameEntity entity) {
        if (entity == null) {
            return;
        }

        BitSet bits = entity.getBucketBits();
        boolean containsEntity = bits.get(this.index);
        boolean acceptsEntity = this.acceptsEntity(entity);
        if (Core.debug && this.verbose) {
            DebugType.Entity
                .println(
                    "testing entity = "
                        + entity.getEntityNetID()
                        + ", type="
                        + entity.getGameEntityType()
                        + ", contains="
                        + containsEntity
                        + ", accepts="
                        + acceptsEntity
                        + ", removing="
                        + entity.removingFromEngine
                );
        }

        if (!entity.removingFromEngine && !containsEntity && acceptsEntity) {
            if (Core.debug && this.verbose) {
                DebugType.Entity.println("adding entity = " + entity.getEntityNetID() + ", type=" + entity.getGameEntityType());
            }

            if (Core.debug && GameEntityManager.debugMode && this.entities.contains(entity, true)) {
                throw new RuntimeException("Entity already exists in bucket.");
            }

            this.entities.add(entity);
            bits.set(this.index);
            entity.setBucketSlot(this.index, this.entities.size - 1);
            if (Core.debug && this.verbose) {
                DebugType.Entity.println("bits = " + bits.get(this.index));
            }

            if (this.listeners.size > 0) {
                for (int i = 0; i < this.listeners.size; i++) {
                    this.listeners.get(i).listener.onBucketEntityAdded(this, entity);
                }
            }
        } else if (containsEntity && (entity.removingFromEngine || !acceptsEntity)) {
            if (Core.debug && this.verbose) {
                DebugType.Entity.println("removing entity = " + entity.getEntityNetID() + ", type=" + entity.getGameEntityType());
            }

            if (Core.debug && GameEntityManager.debugMode && !this.entities.contains(entity, true)) {
                throw new RuntimeException("Entity should exist in bucket but does not.");
            }

            int slot = entity.getBucketSlot(this.index);
            if (Core.debug && GameEntityManager.debugMode) {
                int actualSlot = this.entities.indexOf(entity, true);
                if (actualSlot != slot) {
                    throw new RuntimeException("Cached bucket slot out of sync: cached=" + slot + ", actual=" + actualSlot);
                }
            }

            // === ApocBR: always-on self-healing slot verification ================
            // The cheap O(1) check below (single array read + reference compare)
            // guards the O(1) removal optimization above against any cache desync,
            // regardless of cause (pooling edge cases, ordering bugs, etc). If the
            // cached slot is wrong, fall back to the O(n) linear scan to find the
            // real slot instead of corrupting the bucket by removing/mutating the
            // wrong entity. This keeps removal O(1) in the (expected) common case
            // while making desyncs self-correcting instead of silently producing
            // stale bucket members (e.g. an entity left registered in a component
            // family bucket after that component was removed).
            if (slot < 0 || slot >= this.entities.size || this.entities.get(slot) != entity) {
                int actualSlot = this.entities.indexOf(entity, true);
                DebugType.General
                    .warn(
                        "EntityBucket.updateMembership: cached bucket slot out of sync for entity="
                            + entity.getEntityNetID()
                            + ", bucketIndex="
                            + this.index
                            + ", cachedSlot="
                            + slot
                            + ", actualSlot="
                            + actualSlot
                            + " - falling back to linear scan."
                    );
                slot = actualSlot;
            }
            // =======================================================================

            if (slot >= 0) {
                int lastIndex = this.entities.size - 1;
                GameEntity swapped = this.entities.get(lastIndex);
                this.entities.removeIndex(slot);
                if (swapped != null && swapped != entity) {
                    swapped.setBucketSlot(this.index, slot);
                } else if (swapped == null && slot < this.entities.size) {
                    this.needsNullCompaction = true;
                    this.compactNullEntities();
                }
            } else {
                // Entity's bit says it should be a member, but it is nowhere in the
                // backing array (severe pre-existing desync). Nothing to remove;
                // just clear the stale bit below so it stops being treated as a
                // member instead of throwing/crashing.
                DebugType.General
                    .warn(
                        "EntityBucket.updateMembership: entity="
                            + entity.getEntityNetID()
                            + " marked as bucket member (bucketIndex="
                            + this.index
                            + ") but not found in bucket array - clearing stale bit."
                    );
            }

            bits.clear(this.index);
            if (this.listeners.size > 0) {
                for (int i = 0; i < this.listeners.size; i++) {
                    this.listeners.get(i).listener.onBucketEntityRemoved(this, entity);
                }
            }
        }
    }

    private void compactNullEntities() {
        this.needsNullCompaction = false;
        for (int i = 0; i < this.entities.size;) {
            GameEntity entity = this.entities.get(i);
            if (entity != null) {
                i++;
                continue;
            }

            DebugType.General.warn("EntityBucket.compactNullEntities: removing null entity from bucketIndex=" + this.index + ", slot=" + i);
            int lastIndex = this.entities.size - 1;
            GameEntity swapped = this.entities.get(lastIndex);
            this.entities.removeIndex(i);
            if (swapped != null && i < this.entities.size) {
                swapped.setBucketSlot(this.index, i);
            }
        }
    }

    public final void addListener(int priority, IBucketListener listener) {
        if (!this.listenerSet.contains(listener)) {
            EntityBucket.BucketListenerData data = new EntityBucket.BucketListenerData();
            data.listener = listener;
            data.priority = priority;
            this.listeners.add(data);
            this.listeners.sort(this.listenerComparator);
        }
    }

    public final void removeListener(IBucketListener listener) {
        if (this.listenerSet.remove(listener)) {
            for (int i = 0; i < this.listeners.size; i++) {
                if (this.listeners.get(i).listener == listener) {
                    this.listeners.removeIndex(i);
                    break;
                }
            }
        }
    }

    private static class BucketListenerComparator implements Comparator<EntityBucket.BucketListenerData> {
        public int compare(EntityBucket.BucketListenerData a, EntityBucket.BucketListenerData b) {
            return Integer.compare(a.priority, b.priority);
        }
    }

    private static class BucketListenerData {
        public IBucketListener listener;
        public int priority;
    }

    protected static class CustomBucket extends EntityBucket {
        private final EntityBucket.EntityValidator validator;

        protected CustomBucket(int index, EntityBucket.EntityValidator validator) {
            super(index);
            this.validator = validator;
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return this.validator.acceptsEntity(entity);
        }
    }

    public interface EntityValidator {
        boolean acceptsEntity(GameEntity var1);
    }

    protected static class FamilyBucket extends EntityBucket {
        private final Family family;

        protected FamilyBucket(int index, Family family) {
            super(index);
            this.family = family;
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return this.family.matches(entity);
        }
    }

    protected static class InventoryItemBucket extends EntityBucket {
        protected InventoryItemBucket(int index) {
            super(index);
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return entity instanceof InventoryItem;
        }
    }

    protected static class IsoObjectBucket extends EntityBucket {
        protected IsoObjectBucket(int index) {
            super(index);
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return entity instanceof IsoObject;
        }
    }

    protected static class RendererBucket extends EntityBucket {
        protected RendererBucket(int index) {
            super(index);
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return entity.hasRenderers();
        }
    }

    protected static class VehiclePartBucket extends EntityBucket {
        protected VehiclePartBucket(int index) {
            super(index);
        }

        @Override
        protected final boolean acceptsEntity(GameEntity entity) {
            return entity instanceof VehiclePart;
        }
    }
}
