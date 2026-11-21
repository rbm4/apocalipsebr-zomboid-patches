package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.NpcSettings;
import cloud.apocalipse.pz.agent.features.npc.planning.NpcDecisionPlanner;

/** Server-owned planner lifecycle; NpcActorRuntime binds native factory authority to each session. */
public final class NpcEngine implements AutoCloseable {
    static final long PERIOD = 100_000_000L;
    private final NpcSettings settings;
    private Thread owner;
    private volatile Object world;
    private volatile NpcDecisionPlanner planner;
    private volatile ServerHumanSimulationWorker worker;
    private long nextTick;
    private volatile long generation, serverTicks;
    private volatile boolean stopped;
    private volatile Throwable failure;

    public NpcEngine(NpcSettings settings) { this.settings = java.util.Objects.requireNonNull(settings); }

    /** World is an identity token only; it is never passed to the worker. Null means not ready. */
    public void update(Object currentWorld, long now) {
        if (!settings.enabled() || stopped) return;
        synchronized (this) {
            if (stopped) return;
            if (owner == null) {
                if (currentWorld == null) return;
                owner = Thread.currentThread();
            }
        }
        requireOwner();
        if (world != currentWorld) {
            stopSession();
            synchronized (this) {
                if (stopped || currentWorld == null) return;
                world = currentWorld;
                planner = new NpcDecisionPlanner(settings.asynchronous());
                planner.bindOwner();
                generation++;
                nextTick = now;
                if (settings.asynchronous()) {
                    worker = new ServerHumanSimulationWorker(PERIOD, planner::tick, planner::fail);
                    worker.start();
                }
            }
        }
        var active = planner;
        if (active == null || stopped) return;
        if (active.failure() != null) {
            failure = active.failure();
            close();
            return;
        }
        if (now < nextTick) return;
        nextTick = now + PERIOD;
        active.beginServerTick();
        serverTicks++;
    }

    /** Admission is server-thread only. Future actor adapters own their slots and freshness checks. */
    public NpcDecisionPlanner planner() {
        requireOwner();
        var active = planner;
        if (stopped || active == null || active.failure() != null) throw new IllegalStateException("NPC engine unavailable");
        return active;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("NPC engine requires server owner");
    }

    private void stopSession() {
        ServerHumanSimulationWorker running;
        synchronized (this) {
            if (planner != null) planner.stop();
            running = worker;
            planner = null;
            world = null;
        }
        // Never hold a factory/engine monitor while joining the consumer.
        if (running != null) running.shutdown();
        synchronized (this) { if (worker == running) worker = null; }
    }

    /** Safe off-owner fallback: stop detached work only; no native cleanup or checkpoint is fabricated. */
    @Override public void close() {
        synchronized (this) { stopped = true; }
        stopSession();
    }

    void fail(Throwable problem) {
        failure = java.util.Objects.requireNonNull(problem);
        close();
    }

    public Status status() {
        var running = worker;
        return new Status(settings.enabled(), world != null && !stopped, stopped,
            generation, serverTicks, running == null ? 0 : running.ticks(), failure != null,
            running == null ? 0 : running.lastDurationNanos(),running == null ? 0 : running.overruns());
    }

    public record Status(boolean enabled, boolean started, boolean stopped, long generation,
                         long serverTicks, long workerTicks, boolean failed,
                         long workerLastDurationNanos,long workerOverruns) { }
}
