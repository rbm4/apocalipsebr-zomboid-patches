package zombie.apocalipse.humans;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** One independent NPC clock. No queued ticks, concurrent executions or catch-up bursts. */
final class ServerHumanSimulationWorker {
    private final Thread thread;
    private final long period;
    private final LongConsumer tick;
    private final Consumer<Throwable> failure;
    private volatile boolean stopping;
    private boolean started;
    private volatile long ticks, lastDurationNanos, overruns;
    private volatile Throwable problem;

    ServerHumanSimulationWorker(long period, LongConsumer tick, Consumer<Throwable> failure) {
        if (period <= 0) throw new IllegalArgumentException("Invalid NPC tick period");
        this.period = period;
        this.tick = Objects.requireNonNull(tick);
        this.failure = Objects.requireNonNull(failure);
        thread = new Thread(this::run, "ApocBR-NPC-Simulation");
        thread.setDaemon(true);
    }

    synchronized void start() {
        if (started || stopping) return;
        started = true;
        thread.start();
    }

    boolean isWorkerThread() { return Thread.currentThread() == thread; }
    boolean failed() { return problem != null; }
    long ticks() { return ticks; }
    long lastDurationNanos() { return lastDurationNanos; }
    long overruns() { return overruns; }

    private void run() {
        long next = System.nanoTime() + period;
        try {
            while (!stopping) {
                long remaining = next - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(this, remaining);
                    if (Thread.interrupted() && !stopping) throw new InterruptedException("NPC worker interrupted");
                    continue;
                }
                if (stopping) break;
                long startedAt = System.nanoTime();
                tick.accept(startedAt);
                long finishedAt = System.nanoTime();
                lastDurationNanos = finishedAt - startedAt;
                ticks++;
                // Anchor to actual start, so jitter cannot run sooner than the factory's 100 ms gate.
                next = startedAt + period;
                if (finishedAt - next >= 0) {
                    overruns++;
                    next = finishedAt + period;
                }
            }
        } catch (Throwable caught) {
            problem = caught;
            stopping = true;
            failure.accept(caught);
        }
    }

    /** Called before world teardown; never join while holding the factory monitor. */
    void shutdown() {
        stopping = true;
        thread.interrupt();
        if (isWorkerThread()) throw new IllegalStateException("NPC worker cannot join itself");
        try {
            thread.join(10000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping NPC simulation", interrupted);
        }
        if (thread.isAlive()) throw new IllegalStateException("NPC simulation did not stop before world teardown");
    }
}
