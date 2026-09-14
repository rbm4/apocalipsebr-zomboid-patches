// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.util;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class CappedConcurrentQueue<T> {
    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private volatile int maxSize;

    public CappedConcurrentQueue(int maxSize) {
        this.maxSize = maxSize;
    }

    public boolean add(T item) {
        if (this.count.get() >= this.maxSize) {
            return false;
        } else {
            this.queue.add(item);
            this.count.incrementAndGet();
            return true;
        }
    }

    public T poll() {
        T item = this.queue.poll();
        if (item != null) {
            this.count.decrementAndGet();
        }

        return item;
    }

    public boolean remove(T item) {
        if (this.queue.remove(item)) {
            this.count.decrementAndGet();
            return true;
        } else {
            return false;
        }
    }

    public boolean contains(T item) {
        return this.queue.contains(item);
    }

    public void copyTo(Collection<? super T> dest) {
        dest.addAll(this.queue);
    }

    public boolean isEmpty() {
        return this.queue.isEmpty();
    }

    public int size() {
        return this.count.get();
    }

    public void clear() {
        this.queue.clear();
        this.count.set(0);
    }

    public void setMaxSize(int size) {
        this.maxSize = size;
        this.trimToMaxSize();
    }

    private void trimToMaxSize() {
        while (this.count.get() > this.maxSize) {
            if (this.poll() == null) {
                return;
            }
        }
    }
}
