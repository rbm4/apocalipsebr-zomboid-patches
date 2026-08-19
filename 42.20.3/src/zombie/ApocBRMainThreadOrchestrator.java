package zombie;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

public final class ApocBRMainThreadOrchestrator {
    private static final long PUMP_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
    private final ConcurrentLinkedQueue<ApocBRMainThreadOrchestrator.Task> queue = new ConcurrentLinkedQueue<>();
    private final Semaphore wakeSignal = new Semaphore(0);
    private final String pumpPhase;
    private final String taskPhase;
    private final String idleWaitPhase;

    public ApocBRMainThreadOrchestrator(String pumpPhase, String taskPhase, String idleWaitPhase) {
        this.pumpPhase = pumpPhase;
        this.taskPhase = taskPhase;
        this.idleWaitPhase = idleWaitPhase;
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
        future.join();
    }

    public void signalWorkAvailable() {
        this.wakeSignal.release();
    }

    public void pumpUntil(CountDownLatch latch) {
        this.assertMainThread("pumpUntil");
        long pumpStart = ApocBRServerTelemetry.beginDetail();
        int drained = 0;

        while ((latch != null && latch.getCount() > 0L) || !this.queue.isEmpty()) {
            ApocBRMainThreadOrchestrator.Task task = this.queue.poll();
            if (task != null) {
                this.runTask(task);
                drained++;
                continue;
            }

            if (latch != null && latch.getCount() > 0L) {
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
    }

    public void drainAll() {
        this.assertMainThread("drainAll");
        long pumpStart = ApocBRServerTelemetry.beginDetail();
        int drained = 0;
        ApocBRMainThreadOrchestrator.Task task;
        while ((task = this.queue.poll()) != null) {
            this.runTask(task);
            drained++;
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
