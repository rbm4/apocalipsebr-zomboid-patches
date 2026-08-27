// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import java.util.Objects;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.entity.util.Array;
import zombie.entity.util.ImmutableArray;
import zombie.entity.util.ObjectSet;
import zombie.entity.util.SingleThreadPool;
import zombie.network.GameServer;

public final class EngineEntityManager {
    private static long offMainQueuedOperations;
    private static long lastOffMainQueuedLogMs;
    private final EntityBucketManager bucketManager;
    private final Array<GameEntity> entities = new Array<>(false, 16);
    private final ObjectSet<GameEntity> entitySet = new ObjectSet<>();
    private final ImmutableArray<GameEntity> immutableEntities = new ImmutableArray<>(this.entities);
    private final Array<EngineEntityManager.EntityOperation> pendingOperations = new Array<>(false, 16);
    private final Array<EngineEntityManager.EntityOperation> processingOperations = new Array<>(false, 16);
    private final Object operationsLock = new Object();
    private final EngineEntityManager.EntityOperationPool entityOperationPool = new EngineEntityManager.EntityOperationPool();
    private final ComponentOperationHandler componentOperationHandler;
    private final IBooleanInformer delayed;
    private final IBucketInformer bucketsUpdating;
    private final Engine engine;

    protected EngineEntityManager(Engine engine, IBooleanInformer delayed) {
        this.engine = engine;
        this.bucketManager = new EntityBucketManager(this.immutableEntities);
        this.bucketsUpdating = this.bucketManager.getBucketsUpdatingInformer();
        this.delayed = delayed;
        this.componentOperationHandler = new ComponentOperationHandler(this.delayed, this.bucketsUpdating, new EngineEntityManager.ComponentOperationListener());
    }

    EntityBucketManager getBucketManager() {
        return this.bucketManager;
    }

    void addEntity(GameEntity entity) {
        if (entity == null) {
            return;
        }

        if (!this.shouldQueueOperation()) {
            this.addEntityInternal(entity);
        } else {
            synchronized (this.operationsLock) {
                if (entity.scheduledForEngineRemoval || entity.removingFromEngine) {
                    throw new IllegalArgumentException("Entity is scheduled for removal.");
                }

                if (entity.addedToEngine) {
                    if (Core.debug) {
                        throw new IllegalArgumentException("Entity has already been added to Engine.");
                    }

                    return;
                }

                entity.addedToEngine = true;
                entity.scheduledDelayedAddToEngine = true;
                EngineEntityManager.EntityOperation operation = this.entityOperationPool.obtain();
                operation.entity = entity;
                operation.type = EngineEntityManager.EntityOperation.Type.Add;
                this.pendingOperations.add(operation);
            }
        }
    }

    void removeEntity(GameEntity entity) {
        if (entity == null) {
            return;
        }

        if (!this.shouldQueueOperation()) {
            this.removeEntityInternal(entity);
        } else {
            synchronized (this.operationsLock) {
                if (entity.scheduledForEngineRemoval) {
                    return;
                }

                entity.scheduledForEngineRemoval = true;
                EngineEntityManager.EntityOperation operation = this.entityOperationPool.obtain();
                operation.entity = entity;
                operation.type = EngineEntityManager.EntityOperation.Type.Remove;
                this.pendingOperations.add(operation);
            }
        }
    }

    void removeAllEntities() {
        this.removeAllEntities(this.immutableEntities);
    }

    void removeAllEntities(ImmutableArray<GameEntity> entities) {
        if (!this.shouldQueueOperation()) {
            while (entities.size() > 0) {
                this.removeEntityInternal(entities.first());
            }
        } else {
            synchronized (this.operationsLock) {
                for (GameEntity entity : entities) {
                    if (entity != null) {
                        entity.scheduledForEngineRemoval = true;
                    }
                }

                EngineEntityManager.EntityOperation operation = this.entityOperationPool.obtain();
                operation.type = EngineEntityManager.EntityOperation.Type.RemoveAll;
                operation.entities = entities;
                this.pendingOperations.add(operation);
            }
        }
    }

    ImmutableArray<GameEntity> getEntities() {
        return this.immutableEntities;
    }

    boolean hasPendingOperations() {
        synchronized (this.operationsLock) {
            return this.pendingOperations.size > 0;
        }
    }

    void processPendingOperations() {
        synchronized (this.operationsLock) {
            this.processingOperations.addAll(this.pendingOperations);
            this.pendingOperations.clear();
        }

        try {
            for (int i = 0; i < this.processingOperations.size; i++) {
                EngineEntityManager.EntityOperation operation = this.processingOperations.get(i);
                if (operation == null || operation.type == null) {
                    continue;
                }

                switch (operation.type) {
                    case Add:
                        if (operation.entity != null) {
                            this.addEntityInternal(operation.entity);
                        }
                        break;
                    case Remove:
                        if (operation.entity != null) {
                            this.removeEntityInternal(operation.entity);
                        }
                        break;
                    case RemoveAll:
                        while (operation.entities != null && operation.entities.size() > 0) {
                            this.removeEntityInternal(operation.entities.first());
                        }
                        break;
                    default:
                        throw new AssertionError("Unexpected EntityOperation type");
                }
            }
        } finally {
            synchronized (this.operationsLock) {
                for (int i = 0; i < this.processingOperations.size; i++) {
                    EngineEntityManager.EntityOperation operation = this.processingOperations.get(i);
                    if (operation != null) {
                        this.entityOperationPool.free(operation);
                    }
                }
            }

            this.processingOperations.clear();
        }
    }

    private boolean shouldQueueOperation() {
        boolean offMain = GameServer.server && GameServer.mainThread != null && Thread.currentThread() != GameServer.mainThread;
        if (offMain) {
            recordOffMainQueuedOperation();
        }

        return this.delayed.value() || this.bucketsUpdating.value() || offMain;
    }

    private static void recordOffMainQueuedOperation() {
        synchronized (EngineEntityManager.class) {
            offMainQueuedOperations++;
            long now = System.currentTimeMillis();
            if (now - lastOffMainQueuedLogMs >= 10000L) {
                lastOffMainQueuedLogMs = now;
                DebugType.General.warn("EngineEntityManager: queued off-main entity operation count=" + offMainQueuedOperations);
            }
        }
    }

    void updateOperations() {
        while (this.componentOperationHandler.hasOperationsToProcess() || this.hasPendingOperations()) {
            this.componentOperationHandler.processOperations();
            this.processPendingOperations();
        }
    }

    void addEntityInternal(GameEntity entity) {
        if (entity == null) {
            return;
        }

        if (this.entitySet.contains(entity)) {
            return;
        } else {
            entity.scheduledDelayedAddToEngine = false;
            this.entities.add(entity);
            this.entitySet.add(entity);
            entity.setComponentOperationHandler(this.componentOperationHandler);
            entity.addedToEngine = true;
            this.bucketManager.updateBucketMembership(entity);
            this.engine.onEntityAdded(entity);
        }
    }

    void removeEntityInternal(GameEntity entity) {
        if (entity == null) {
            return;
        }

        boolean removed = this.entitySet.remove(entity);
        if (removed) {
            entity.scheduledForEngineRemoval = false;
            entity.removingFromEngine = true;
            this.entities.removeValue(entity, true);
            this.bucketManager.updateBucketMembership(entity);
            entity.setComponentOperationHandler(null);
            entity.removingFromEngine = false;
            entity.addedToEngine = false;
            this.engine.onEntityRemoved(entity);
        }
    }

    private class ComponentOperationListener implements ComponentOperationHandler.OperationListener {
        private ComponentOperationListener() {
            Objects.requireNonNull(EngineEntityManager.this);
            super();
        }

        @Override
        public void componentsChanged(GameEntity entity) {
            EngineEntityManager.this.bucketManager.updateBucketMembership(entity);
        }
    }

    private static class EntityOperation implements SingleThreadPool.Poolable {
        EngineEntityManager.EntityOperation.Type type;
        GameEntity entity;
        ImmutableArray<GameEntity> entities;

        @Override
        public void reset() {
            this.type = null;
            this.entity = null;
            this.entities = null;
        }

        public static enum Type {
            Add,
            Remove,
            RemoveAll;
        }
    }

    private static class EntityOperationPool extends SingleThreadPool<EngineEntityManager.EntityOperation> {
        protected EngineEntityManager.EntityOperation newObject() {
            return new EngineEntityManager.EntityOperation();
        }
    }
}
