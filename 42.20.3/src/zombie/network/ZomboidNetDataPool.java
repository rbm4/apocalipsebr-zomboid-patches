// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: see the ApocBR comments below.
package zombie.network;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * ApocBR notes.
 *
 * <p>Vanilla behaviour and why it is dangerous on a swapping host:
 *
 * <ul>
 *   <li>The small pool starts empty and is only refilled by {@code discard()}, which runs on the
 *       main thread after a packet has been processed. When the main thread stalls, the pool
 *       drains and every incoming packet allocates a fresh 2 KB {@code ByteBuffer} on the
 *       UdpEngine thread. The allocation rate therefore spikes at exactly the moment the
 *       collector is least able to keep up, and the thread that stalls in the allocator is the
 *       one holding all the connections open.
 *   <li>{@code getLong()} always allocated and {@code discard()} only ever accepted buffers of
 *       capacity 2048, so every packet larger than 2 KB was pure garbage, every time.
 * </ul>
 *
 * <p>This version pre-seeds the small pool, caps it so it cannot become a leak, and adds
 * power-of-two size classes for the large path so big packets are recycled too.
 */
public class ZomboidNetDataPool {
    public static final ZomboidNetDataPool instance = new ZomboidNetDataPool();

    private static final int SMALL_CAPACITY = 2048;
    /**
     * Number of small buffers pre-allocated at startup.
     *
     * <p>Sizing rule: this must cover the sum of the GameServer main-loop queue caps, because
     * during a main-thread stall every buffer migrates from this pool into those queues. With the
     * default caps (4096 player + 16384 high + 4096 normal = 24576) a prewarm of 26624 guarantees
     * the pool never empties, so the UdpEngine thread provably never allocates on the receive
     * path no matter how long the stall lasts. 26624 * 2 KB is about 54 MB, committed once at
     * startup and then permanently reused. Do not size this from a raw packets-per-second burst
     * estimate: the queue caps, not the burst, determine the ceiling.
     */
    private static final int PREWARM_COUNT = Math.max(0, Integer.getInteger("apocbr.netDataPrewarm", 26624));
    /** Hard ceiling on retained small buffers so a burst cannot grow the pool without bound. */
    private static final int MAX_POOLED = Math.max(PREWARM_COUNT, Integer.getInteger("apocbr.netDataMaxPooled", 32768));
    /** Smallest large size class. Anything above SMALL_CAPACITY rounds up into these. */
    private static final int LARGE_MIN_SHIFT = 12;
    /** Largest recycled size class, 1 MB. Bigger packets are left to the collector. */
    private static final int LARGE_MAX_SHIFT = 20;
    private static final int LARGE_CLASSES = LARGE_MAX_SHIFT - LARGE_MIN_SHIFT + 1;
    /** Per size class ceiling. Large packets are rare, so a shallow pool is enough. */
    private static final int MAX_POOLED_LARGE = Math.max(0, Integer.getInteger("apocbr.netDataMaxPooledLarge", 64));

    final ConcurrentLinkedQueue<ZomboidNetData> pool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooled = new AtomicInteger();

    private final ConcurrentLinkedQueue<ZomboidNetData>[] largePools = newLargePools();
    private final AtomicInteger[] largePooled = newLargeCounters();

    private final LongAdder smallMisses = new LongAdder();
    private final LongAdder largeMisses = new LongAdder();
    private final LongAdder largeHits = new LongAdder();

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<ZomboidNetData>[] newLargePools() {
        ConcurrentLinkedQueue<ZomboidNetData>[] queues = new ConcurrentLinkedQueue[LARGE_CLASSES];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ConcurrentLinkedQueue<>();
        }

        return queues;
    }

    private static AtomicInteger[] newLargeCounters() {
        AtomicInteger[] counters = new AtomicInteger[LARGE_CLASSES];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new AtomicInteger();
        }

        return counters;
    }

    /**
     * ApocBR: allocate the small pool up front, on the calling thread, before any client is
     * connected. This keeps the steady-state receive path allocation free and puts the cost in
     * startup where it is harmless.
     */
    public void prewarm() {
        int missing = PREWARM_COUNT - this.pooled.get();
        for (int i = 0; i < missing; i++) {
            this.pool.add(new ZomboidNetData(SMALL_CAPACITY));
            this.pooled.incrementAndGet();
        }
    }

    public ZomboidNetData get() {
        ZomboidNetData data = this.pool.poll();
        if (data == null) {
            this.smallMisses.increment();
            return new ZomboidNetData();
        } else {
            this.pooled.decrementAndGet();
            return data;
        }
    }

    public void discard(ZomboidNetData data) {
        if (data == null) {
            return;
        }

        data.reset();
        int capacity = data.buffer.capacity();
        if (capacity == SMALL_CAPACITY) {
            // ApocBR: bounded, so a flood cannot turn the pool itself into the leak.
            if (this.pooled.get() < MAX_POOLED) {
                this.pool.add(data);
                this.pooled.incrementAndGet();
            }

            return;
        }

        // ApocBR: vanilla dropped every oversized buffer here. Recycle the ones that fit a class.
        int index = largeClassIndex(capacity);
        if (index >= 0 && this.largePooled[index].get() < MAX_POOLED_LARGE) {
            this.largePools[index].add(data);
            this.largePooled[index].incrementAndGet();
        }
    }

    public ZomboidNetData getLong(int len) {
        int index = largeClassIndexForLength(len);
        if (index < 0) {
            this.largeMisses.increment();
            return new ZomboidNetData(len);
        }

        ZomboidNetData data = this.largePools[index].poll();
        if (data == null) {
            this.largeMisses.increment();
            return new ZomboidNetData(1 << (LARGE_MIN_SHIFT + index));
        } else {
            this.largePooled[index].decrementAndGet();
            this.largeHits.increment();
            return data;
        }
    }

    /** Size class for a buffer we already hold, or -1 when it is not an exact class size. */
    private static int largeClassIndex(int capacity) {
        if (capacity < (1 << LARGE_MIN_SHIFT) || capacity > (1 << LARGE_MAX_SHIFT)) {
            return -1;
        }

        if ((capacity & capacity - 1) != 0) {
            return -1;
        }

        return Integer.numberOfTrailingZeros(capacity) - LARGE_MIN_SHIFT;
    }

    /** Size class able to hold {@code len} bytes, or -1 when the request is larger than any class. */
    private static int largeClassIndexForLength(int len) {
        if (len > (1 << LARGE_MAX_SHIFT)) {
            return -1;
        }

        int shift = Math.max(LARGE_MIN_SHIFT, 32 - Integer.numberOfLeadingZeros(Math.max(1, len - 1)));
        return shift > LARGE_MAX_SHIFT ? -1 : shift - LARGE_MIN_SHIFT;
    }

    public int getPooledCount() {
        return this.pooled.get();
    }

    public long getSmallMisses() {
        return this.smallMisses.sum();
    }

    public long getLargeMisses() {
        return this.largeMisses.sum();
    }

    public long getLargeHits() {
        return this.largeHits.sum();
    }
}
