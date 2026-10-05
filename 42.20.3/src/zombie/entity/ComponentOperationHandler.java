// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.debug.DebugType;
import zombie.entity.util.Array;
import zombie.entity.util.SingleThreadPool;
import zombie.network.GameServer;

public final class ComponentOperationHandler {
    private static long offMainQueuedOperations;
    private static long lastOffMainQueuedLogMs;
    private final ComponentOperationHandler.OperationListener operationListener;
    private final IBooleanInformer delayed;
    private final IBucketInformer bucketsUpdating;
    private final ComponentOperationHandler.ComponentOperationPool operationPool = new ComponentOperationHandler.ComponentOperationPool();
    private final Array<ComponentOperationHandler.ComponentOperation> operations = new Array<>();
    private final Array<ComponentOperationHandler.ComponentOperation> processingOperations = new Array<>();
    private final Object operationsLock = new Object();

    protected ComponentOperationHandler(IBooleanInformer delayed, IBucketInformer bucketsUpdating, ComponentOperationHandler.OperationListener listener) {
        this.delayed = delayed;
        this.bucketsUpdating = bucketsUpdating;
        this.operationListener = listener;
    }

    void add(GameEntity entity) {
        if (entity == null) {
            return;
        }

        boolean queueOperation = this.shouldQueueOperation();
        if (this.bucketsUpdating.value() && !queueOperation) {
            throw new IllegalStateException("Cannot perform component operation when buckets are updating.");
        } else {
            if (queueOperation) {
                synchronized (this.operationsLock) {
                    if (entity.scheduledForBucketUpdate) {
                        return;
                    }

                    entity.scheduledForBucketUpdate = true;
                    ComponentOperationHandler.ComponentOperation operation = this.operationPool.obtain();
                    operation.make(entity);
                    this.operations.add(operation);
                }
            } else {
                this.operationListener.componentsChanged(entity);
            }
        }
    }

    void remove(GameEntity entity) {
        if (entity == null) {
            return;
        }

        boolean queueOperation = this.shouldQueueOperation();
        if (this.bucketsUpdating.value() && !queueOperation) {
            throw new IllegalStateException("Cannot perform component operation when buckets are updating.");
        } else {
            if (queueOperation) {
                synchronized (this.operationsLock) {
                    if (entity.scheduledForBucketUpdate) {
                        return;
                    }

                    entity.scheduledForBucketUpdate = true;
                    ComponentOperationHandler.ComponentOperation operation = this.operationPool.obtain();
                    operation.make(entity);
                    this.operations.add(operation);
                }
            } else {
                this.operationListener.componentsChanged(entity);
            }
        }
    }

    boolean hasOperationsToProcess() {
        synchronized (this.operationsLock) {
            return this.operations.size > 0;
        }
    }

    void processOperations() {
        synchronized (this.operationsLock) {
            this.processingOperations.addAll(this.operations);
            this.operations.clear();
        }

        try {
            for (int i = 0; i < this.processingOperations.size; i++) {
                ComponentOperationHandler.ComponentOperation operation = this.processingOperations.get(i);
                if (operation == null || operation.entity == null) {
                    continue;
                }

                this.operationListener.componentsChanged(operation.entity);
                operation.entity.scheduledForBucketUpdate = false;
            }
        } finally {
            synchronized (this.operationsLock) {
                for (int i = 0; i < this.processingOperations.size; i++) {
                    ComponentOperationHandler.ComponentOperation operation = this.processingOperations.get(i);
                    if (operation != null) {
                        this.operationPool.free(operation);
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

        return this.delayed.value() || offMain;
    }

    private static void recordOffMainQueuedOperation() {
        synchronized (ComponentOperationHandler.class) {
            offMainQueuedOperations++;
            long now = System.currentTimeMillis();
            if (now - lastOffMainQueuedLogMs >= 10000L) {
                lastOffMainQueuedLogMs = now;
                DebugType.General.warn("ComponentOperationHandler: queued off-main component operation count=" + offMainQueuedOperations);
            }
        }
    }

    private static class ComponentOperation implements SingleThreadPool.Poolable {
        public GameEntity entity;

        public void make(GameEntity entity) {
            this.entity = entity;
        }

        @Override
        public void reset() {
            this.entity = null;
        }
    }

    private static class ComponentOperationPool extends SingleThreadPool<ComponentOperationHandler.ComponentOperation> {
        protected ComponentOperationHandler.ComponentOperation newObject() {
            return new ComponentOperationHandler.ComponentOperation();
        }
    }

    interface OperationListener {
        void componentsChanged(GameEntity arg0);
    }
}
