package zombie;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

public final class ApocBRMainThreadOrchestrator {
    private static final long PUMP_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long PUMP_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(1000L, Long.getLong("apocbr.load2PumpTimeoutMs", 1000L)));
    private static final long TASK_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(1000L, Long.getLong("apocbr.mainThreadTaskTimeoutMs", 1000L)));
    /**
     * ApocBR: timeout for a pump that drains cooperatively across ticks rather than blocking.
     *
     * The 1s default above assumes the main thread is sitting in pumpUntil() and will therefore
     * service a handoff almost immediately; anything slower means it is wedged. That assumption does
     * not hold for a budgeted pump. Measured load2 costs ~386 main-thread tasks and ~11.6ms of
     * main-thread work per cell, so at an 8ms/tick budget a colour group of 8 cells legitimately
     * takes ~12 ticks (~1.2s) to drain. Under the 1s timeout the workers would start throwing and
     * RecalcAll2's catch would cancel the cells - trading a stall for a cancellation storm. This
     * bound therefore only exists to catch a genuinely stopped main thread.
     */
    private static final long COOPERATIVE_TASK_TIMEOUT_NANOS = TimeUnit.MILLISECONDS
        .toNanos(Math.max(1000L, Long.getLong("apocbr.cooperativeMainThreadTaskTimeoutMs", 30000L)));
    private final ConcurrentLinkedQueue<ApocBRMainThreadOrchestrator.Task> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore wakeSignal = new Semaphore(0);
    private final String pumpPhase;
    private final String taskPhase;
    private final String idleWaitPhase;
    private final long taskTimeoutNanos;
    /**
     * ApocBR: reentrancy guard for the drain loop.
     *
     * Queued tasks are not inert - LuaEvent.LoadChunk, SGlobalObjects.chunkLoaded and
     * MapObjects.loadGridSquare all run Lua. Once tick-phase anchors exist, that Lua can reach code
     * containing an anchor and re-enter drainAll()/pumpFor() from inside a running task, so tasks
     * would nest and complete out of order under each other's stacks. Main-thread only, so a plain
     * field is enough; a nested call simply does nothing and the outer loop picks the work up.
     */
    private boolean draining;

    public ApocBRMainThreadOrchestrator(String pumpPhase, String taskPhase, String idleWaitPhase) {
        this(pumpPhase, taskPhase, idleWaitPhase, false);
    }

    /**
     * @param cooperative true when the owner drains this queue with {@link #pumpFor} across several
     *                    ticks instead of blocking in {@link #pumpUntil}; see
     *                    {@link #COOPERATIVE_TASK_TIMEOUT_NANOS}.
     */
    public ApocBRMainThreadOrchestrator(String pumpPhase, String taskPhase, String idleWaitPhase, boolean cooperative) {
        this.pumpPhase = pumpPhase;
        this.taskPhase = taskPhase;
        this.idleWaitPhase = idleWaitPhase;
        this.taskTimeoutNanos = cooperative ? COOPERATIVE_TASK_TIMEOUT_NANOS : TASK_TIMEOUT_NANOS;
    }

    public void submit(String label, Runnable runnable) {
        if (runnable == null) {
            return;
        }

        this.queue.offer(new ApocBRMainThreadOrchestrator.Task(label, runnable, null));
        this.wakeSignal.release();
        ApocBRServerTelemetry.recordMainThreadTaskSubmitted(label);
    }

    public void submitAndWait(String label, Runnable runnable) {
        if (runnable == null) {
            return;
        }

        if (Thread.currentThread() == GameServer.mainThread) {
            long start = ApocBRServerTelemetry.beginDetail();
            try {
                runnable.run();
            } finally {
                long nanos = System.nanoTime() - start;
                ApocBRServerTelemetry.recordServerMapPrePhase(this.taskPhase, 1, nanos);
                ApocBRServerTelemetry.recordMainThreadTaskDrained(label, nanos);
            }
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        this.queue.offer(new ApocBRMainThreadOrchestrator.Task(label, runnable, future));
        this.wakeSignal.release();
        ApocBRServerTelemetry.recordMainThreadTaskSubmitted(label);
        try {
            future.get(this.taskTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for main-thread task " + label, e);
        } catch (TimeoutException e) {
            RuntimeException timeout = new RuntimeException(
                "Timed out waiting for main-thread task "
                    + label
                    + " after "
                    + TimeUnit.NANOSECONDS.toMillis(this.taskTimeoutNanos)
                    + " ms"
            );
            future.completeExceptionally(timeout);
            DebugLog.log("[ApocBR] " + timeout.getMessage());
            throw timeout;
        } catch (ExecutionException e) {
            throw new RuntimeException("Main-thread task failed " + label, e.getCause());
        }
    }

    public void signalWorkAvailable() {
        this.wakeSignal.release();
    }

    public boolean pumpUntil(CountDownLatch latch) {
        this.assertMainThread("pumpUntil");
        long pumpStart = ApocBRServerTelemetry.beginDetail();
        long deadline = System.nanoTime() + PUMP_TIMEOUT_NANOS;
        int drained = 0;
        boolean timedOut = false;

        while ((latch != null && latch.getCount() > 0L) || !this.queue.isEmpty()) {
            ApocBRMainThreadOrchestrator.Task task = this.queue.poll();
            if (task != null) {
                this.runTask(task);
                drained++;
                continue;
            }

            if (latch != null && latch.getCount() > 0L) {
                if (System.nanoTime() >= deadline) {
                    timedOut = true;
                    DebugLog.log(
                        "[ApocBR] Main-thread orchestrator "
                            + this.pumpPhase
                            + " timed out after "
                            + TimeUnit.NANOSECONDS.toMillis(PUMP_TIMEOUT_NANOS)
                            + " ms, latchRemaining="
                            + latch.getCount()
                            + ", queued="
                            + this.queue.size()
                    );
                    break;
                }

                if (this.queue.isEmpty()) {
                    this.wakeSignal.drainPermits();
                }

                long waitStart = ApocBRServerTelemetry.beginDetail();
                try {
                    this.wakeSignal.tryAcquire(PUMP_WAIT_NANOS, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    ApocBRServerTelemetry.recordServerMapPrePhaseSince(this.idleWaitPhase, 1, waitStart);
                }
            }
        }

        ApocBRServerTelemetry.recordServerMapPrePhaseSince(this.pumpPhase, drained, pumpStart);
        return !timedOut;
    }

    /**
     * ApocBR: bounded-budget, non-parking alternative to {@link #pumpUntil(CountDownLatch)}.
     *
     * pumpUntil() holds the main thread until the latch clears, sleeping in 1ms slices whenever the
     * queue runs dry, and on expiry its caller destroys the whole cell group. Telemetry showed the
     * cost of that shape directly: load2PumpIdleWait burned ~11ms per tick parked on an empty queue,
     * and a single load2 call peaked at 512ms. A stall that long is not just its own cost - GameTime
     * then reports a huge delta so EntitySimulation runs several sim ticks at once, the 70ms packet
     * budget in GameServer.main starts dropping, and ServerLOS backs up, so the following ticks are
     * spent catching up.
     *
     * pumpFor() never parks. It runs whatever the workers have already handed over, stops once
     * budgetNanos is spent or the queue is dry, and returns. An exhausted budget means "not finished
     * yet", never "failed", so the caller resumes on a later tick instead of discarding work. The
     * deadline is only checked after a task completes: tasks are indivisible, so the budget bounds
     * how much work is started, not how long a single task may run.
     *
     * @return true when the latch has cleared and nothing is left queued, i.e. the caller may advance.
     */
    public boolean pumpFor(CountDownLatch latch, long budgetNanos) {
        boolean latchClear = latch == null || latch.getCount() == 0L;
        if (latchClear && this.queue.isEmpty()) {
            return true;
        }

        if (this.draining) {
            return false;
        }

        this.assertMainThread("pumpFor");
        long pumpStart = ApocBRServerTelemetry.beginDetail();
        long deadline = System.nanoTime() + budgetNanos;
        int drained = 0;

        this.draining = true;
        try {
            ApocBRMainThreadOrchestrator.Task task;
            while ((task = this.queue.poll()) != null) {
                this.runTask(task);
                drained++;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
        } finally {
            this.draining = false;
        }

        ApocBRServerTelemetry.recordServerMapPrePhaseSince(this.pumpPhase, drained, pumpStart);
        return (latch == null || latch.getCount() == 0L) && this.queue.isEmpty();
    }

    public void drainAll() {
        // ApocBR: fast path. drainAll() is called from tick-phase anchors, not just from the load
        // pipeline, so the common case is an empty queue. Bail out before assertMainThread() and
        // beginDetail() (a nanoTime call) so an anchor that finds nothing to do costs a single
        // volatile read instead of a telemetry round trip.
        if (this.queue.isEmpty() || this.draining) {
            return;
        }

        this.assertMainThread("drainAll");
        long pumpStart = ApocBRServerTelemetry.beginDetail();
        int drained = 0;

        this.draining = true;
        try {
            ApocBRMainThreadOrchestrator.Task task;
            while ((task = this.queue.poll()) != null) {
                this.runTask(task);
                drained++;
            }
        } finally {
            this.draining = false;
        }

        ApocBRServerTelemetry.recordServerMapPrePhaseSince(this.pumpPhase, drained, pumpStart);
    }

    private void runTask(ApocBRMainThreadOrchestrator.Task task) {
        this.assertMainThread("runTask");
        long start = ApocBRServerTelemetry.beginDetail();
        try {
            task.runnable.run();
            if (task.future != null) {
                task.future.complete(null);
            }
        } catch (Throwable throwable) {
            ExceptionLogger.logException(throwable);
            if (task.future != null) {
                task.future.completeExceptionally(throwable);
            }
        } finally {
            long nanos = System.nanoTime() - start;
            ApocBRServerTelemetry.recordServerMapPrePhase(this.taskPhase, 1, nanos);
            ApocBRServerTelemetry.recordMainThreadTaskDrained(task.label, nanos);
        }
    }

    private void assertMainThread(String operation) {
        if (Thread.currentThread() != GameServer.mainThread) {
            DebugLog.log(
                "[ApocBR] Main-thread orchestrator " + operation + " called from "
                    + Thread.currentThread().getName()
                    + ", expected "
                    + (GameServer.mainThread == null ? "main" : GameServer.mainThread.getName())
            );
        }
    }

    private static final class Task {
        private final String label;
        private final Runnable runnable;
        private final CompletableFuture<Void> future;

        private Task(String label, Runnable runnable, CompletableFuture<Void> future) {
            this.label = label == null ? "unknown" : label;
            this.runnable = runnable;
            this.future = future;
        }
    }
}
