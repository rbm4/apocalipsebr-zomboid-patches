package zombie.popman;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import zombie.ApocBRServerTelemetryLite;

/** Workers own only frozen wire data and a transport endpoint, never world objects. */
public final class ServerZombieAsyncSender {
    public static final int MAX_PACKET_BYTES = 1000000;
    private final Semaphore capacity;
    private final ThreadPoolExecutor workers;
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ByteBuffer> buffers = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(MAX_PACKET_BYTES));

    @FunctionalInterface
    public interface Transport {
        int send(ByteBuffer data, int priority, int reliability, byte ordering);
    }

    public static final class Lane {
        private final Transport transport;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean busy = new AtomicBoolean();

        public Lane(Transport transport) { this.transport = transport; }
        public void close() { this.active.set(false); }
    }

    /** Defensive copies also freeze nested/optional zombie fields as exact vanilla bytes. */
    public static final class Message {
        public final short id;
        public final int size;
        private final int priority;
        private final int reliability;
        private final byte ordering;
        private final byte[][] parts;

        public Message(short id, int priority, int reliability, byte ordering, byte[]... parts) {
            this.id = id;
            this.priority = priority;
            this.reliability = reliability;
            this.ordering = ordering;
            this.parts = new byte[parts.length][];
            int size = 3;
            for (int i = 0; i < parts.length; i++) {
                size = Math.addExact(size, parts[i].length);
                if (size > MAX_PACKET_BYTES) throw new IllegalArgumentException("Zombie packet exceeds vanilla buffer");
                this.parts[i] = parts[i].clone();
            }
            this.size = size;
        }

        public void write(ByteBuffer buffer) {
            buffer.clear();
            buffer.put((byte)134).putShort(this.id);
            for (byte[] part : this.parts) buffer.put(part);
            buffer.flip();
        }
    }

    public static final class Completion {
        public final Lane lane;
        public final List<Message> messages;
        public final int attempted;
        public final boolean success;
        private Completion(Lane lane, List<Message> messages, int attempted, boolean success) {
            this.lane = lane;
            this.messages = messages;
            this.attempted = attempted;
            this.success = success;
        }
    }

    public ServerZombieAsyncSender(int threads, int capacity) {
        this.capacity = new Semaphore(capacity);
        this.workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), task -> {
                Thread thread = new Thread(task, "ApocBR-ZombieSend");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Reserve before consuming live requests or advancing connection timers. Never waits. */
    public boolean reserve(Lane lane) {
        if (!lane.active.get() || this.workers.isShutdown() || !lane.busy.compareAndSet(false, true)) return false;
        if (!this.capacity.tryAcquire()) {
            lane.busy.set(false);
            return false;
        }
        return true;
    }

    public void release(Lane lane) {
        lane.busy.set(false);
        this.capacity.release();
    }

    public void submit(Lane lane, List<Message> messages) {
        List<Message> frozen = List.copyOf(messages);
        long submitted = System.nanoTime();
        try {
            this.workers.execute(() -> {
                ApocBRServerTelemetryLite.recordPhase("network.zombies.async.queueDelay", System.nanoTime() - submitted);
                int attempted = 0;
                boolean success = true;
                try (ApocBRServerTelemetryLite.Scope scope = ApocBRServerTelemetryLite.phase("network.zombies.async.send")) {
                    ByteBuffer buffer = this.buffers.get();
                    for (Message message : frozen) {
                        if (!lane.active.get()) { success = false; break; }
                        try (ApocBRServerTelemetryLite.Scope assembly = ApocBRServerTelemetryLite.phase("network.zombies.async.assemble")) {
                            message.write(buffer);
                        }
                        if (!lane.active.get()) { success = false; break; }
                        attempted++;
                        try (ApocBRServerTelemetryLite.Scope transport = ApocBRServerTelemetryLite.phase("network.zombies.async.transport")) {
                            if (lane.transport.send(buffer, message.priority, message.reliability, message.ordering) <= 0) {
                                success = false;
                                break;
                            }
                        }
                    }
                } catch (Exception exception) {
                    success = false;
                    ApocBRServerTelemetryLite.count("zombies.async.exceptions", 1);
                } catch (Error error) {
                    success = false;
                    throw error;
                } finally {
                    if (!success) ApocBRServerTelemetryLite.count("zombies.async.failed", 1);
                    this.completions.offer(new Completion(lane, frozen, attempted, success));
                    // Capacity remains reserved until the main thread consumes the completion.
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            this.completions.offer(new Completion(lane, frozen, 0, false));
        }
    }

    public Completion poll() { return this.completions.poll(); }

    public void shutdown() {
        // Called before peer shutdown; in-flight native calls must finish before destroying the peer.
        this.workers.shutdown();
        boolean interrupted = false;
        while (!this.workers.isTerminated()) {
            try { this.workers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
