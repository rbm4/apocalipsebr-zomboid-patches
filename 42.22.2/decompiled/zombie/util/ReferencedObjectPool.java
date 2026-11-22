// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util;

import java.util.function.Supplier;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.network.statistics.counters.ObjectPoolCounter;

public class ReferencedObjectPool<T extends ReferencedObject> extends ObjectPoolCounter {
    private final CappedConcurrentQueue<T> released;
    private final Supplier<T> allocator;

    public ReferencedObjectPool(Supplier<T> allocator, String name) {
        this(allocator, name, 1024);
    }

    public ReferencedObjectPool(Supplier<T> allocator, String name, int maxSize) {
        super(name);
        this.allocator = allocator;
        this.released = new CappedConcurrentQueue<>(maxSize);
    }

    public T alloc() {
        T obj = this.released.poll();
        if (obj == null) {
            return this.create();
        } else if (obj.getReferenceCount() == 0) {
            obj.retain();
            return obj;
        } else {
            if (Core.debug) {
                DebugType.General.printStackTrace("Object is referenced " + obj.getReferenceCount() + " times");
            }

            return this.create();
        }
    }

    public void release(T obj) {
        if (obj.getReferenceCount() == 1) {
            obj.release();
            this.released.add(obj);
        } else if (Core.debug) {
            DebugType.General.printStackTrace("Object is referenced " + obj.getReferenceCount() + " times");
        }
    }

    @Override
    public int size() {
        return this.released.size();
    }

    private T create() {
        T obj = this.allocator.get();
        obj.retain();
        return obj;
    }
}
