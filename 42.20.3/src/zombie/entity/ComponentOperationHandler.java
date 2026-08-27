// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.entity.util.Array;
import zombie.entity.util.SingleThreadPool;

public final class ComponentOperationHandler {
    private final ComponentOperationHandler.OperationListener operationListener;
    private final IBooleanInformer delayed;
    private final IBucketInformer bucketsUpdating;
    private final ComponentOperationHandler.ComponentOperationPool operationPool = new ComponentOperationHandler.ComponentOperationPool();
    private final Array<ComponentOperationHandler.ComponentOperation> operations = new Array<>();
    private final Array<ComponentOperationHandler.ComponentOperation> processingOperations = new Array<>();

    protected ComponentOperationHandler(IBooleanInformer delayed, IBucketInformer bucketsUpdating, ComponentOperationHandler.OperationListener listener) {
        this.delayed = delayed;
        this.bucketsUpdating = bucketsUpdating;
        this.operationListener = listener;
    }

    void add(GameEntity entity) {
        if (entity == null) {
            return;
        }

        if (this.bucketsUpdating.value()) {
            throw new IllegalStateException("Cannot perform component operation when buckets are updating.");
        } else {
            if (this.delayed.value()) {
                if (entity.scheduledForBucketUpdate) {
                    return;
                }

                entity.scheduledForBucketUpdate = true;
                ComponentOperationHandler.ComponentOperation operation = this.operationPool.obtain();
                operation.make(entity);
                this.operations.add(operation);
            } else {
                this.operationListener.componentsChanged(entity);
            }
        }
    }

    void remove(GameEntity entity) {
        if (entity == null) {
            return;
        }

        if (this.bucketsUpdating.value()) {
            throw new IllegalStateException("Cannot perform component operation when buckets are updating.");
        } else {
            if (this.delayed.value()) {
                if (entity.scheduledForBucketUpdate) {
                    return;
                }

                entity.scheduledForBucketUpdate = true;
                ComponentOperationHandler.ComponentOperation operation = this.operationPool.obtain();
                operation.make(entity);
                this.operations.add(operation);
            } else {
                this.operationListener.componentsChanged(entity);
            }
        }
    }

    boolean hasOperationsToProcess() {
        return this.operations.size > 0;
    }

    void processOperations() {
        this.processingOperations.addAll(this.operations);
        this.operations.clear();

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
            for (int i = 0; i < this.processingOperations.size; i++) {
                ComponentOperationHandler.ComponentOperation operation = this.processingOperations.get(i);
                if (operation != null) {
                    this.operationPool.free(operation);
                }
            }

            this.processingOperations.clear();
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
