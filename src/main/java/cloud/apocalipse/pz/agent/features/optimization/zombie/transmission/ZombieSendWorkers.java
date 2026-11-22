package cloud.apocalipse.pz.agent.features.optimization.zombie.transmission;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ZombieSendWorkers {
    static final int MAX_BYTES = 1_000_000;
    private final ThreadPoolExecutor pool;
    private final Semaphore permits;
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ByteBuffer> buffers = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(MAX_BYTES));

    ZombieSendWorkers(int threads, int capacity) {
        pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
            Thread.ofPlatform().daemon().name("ApocBR-ZombieSend-", 1).factory());
        permits = new Semaphore(capacity);
    }

    boolean reserve(Lane lane) {
        if (lane.busy || !lane.active || !permits.tryAcquire()) {
            return false;
        }
        lane.busy = true;
        return true;
    }

    void release(Lane lane) {
        lane.busy = false;
        permits.release();
    }

    void submit(Lane lane, List<Message> messages) {
        List<Message> frozen = List.copyOf(messages);
        long queued = System.nanoTime();
        try {
            pool.execute(() -> transmit(lane, frozen, queued));
        } catch (RejectedExecutionException failure) {
            completions.add(new Completion(lane, frozen, 0, false));
        }
    }

    private void transmit(Lane lane, List<Message> messages, long queued) {
        OptimizationTelemetry.Phase.ZOMBIE_SEND_QUEUE.recordSince(queued);
        int attempted = 0;
        boolean success = false;
        try {
            ByteBuffer buffer = buffers.get();
            for (Message message : messages) {
                if (!lane.active) {
                    return;
                }
                buffer.clear();
                buffer.put(message.bytes).flip();
                synchronized (lane) {
                    if (!lane.active) {
                        return;
                    }
                    attempted++;
                    long started = System.nanoTime();
                    try {
                        if (lane.transport.send(buffer, message.priority, message.reliability, message.ordering) <= 0) {
                            return;
                        }
                    } finally {
                        OptimizationTelemetry.Phase.ZOMBIE_SEND_TRANSPORT.recordSince(started);
                    }
                }
            }
            success = true;
        } catch (RuntimeException failure) {
            System.err.println("[ApocBR-Agent][ZombieSend] Transport failed: " + failure);
        } finally {
            if (!success) {
                OptimizationTelemetry.count(OptimizationTelemetry.Counter.ZOMBIE_SEND_FAILED);
            }
            completions.add(new Completion(lane, messages, attempted, success));
        }
    }

    Completion poll() {
        return completions.poll();
    }

    void shutdown() {
        pool.shutdown();
        boolean interrupted = false;
        while (!pool.isTerminated()) {
            try {
                pool.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    interface Transport {
        int send(ByteBuffer buffer, int priority, int reliability, byte ordering);
    }

    static final class Lane {
        final Transport transport;
        volatile boolean active = true;
        boolean busy; // Simulation owner only.

        Lane(Transport transport) {
            this.transport = transport;
        }

        synchronized void close() {
            active = false;
        }
    }

    record Message(short id, byte[] bytes, int priority, int reliability, byte ordering) {
    }

    record Completion(Lane lane, List<Message> messages, int attempted, boolean success) {
    }
}
