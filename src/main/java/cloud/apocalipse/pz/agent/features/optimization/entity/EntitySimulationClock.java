package cloud.apocalipse.pz.agent.features.optimization.entity;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import zombie.entity.EntitySimulation;
import zombie.entity.GameEntity;
import zombie.network.GameServer;

/** Scope of a single native GameEntityManager.Update call on the simulation owner. */
public final class EntitySimulationClock {
    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();
    private static int interval = 10;
    private static long ticksSinceReset;

    private EntitySimulationClock() {
    }

    static void configure() {
        try {
            interval = Math.max(1, Integer.parseInt(AgentConfiguration.systemProperty(
                AgentConfiguration.META_SIMULATION_INTERVAL_PROPERTY, "10")));
        } catch (NumberFormatException invalid) {
            interval = 10;
        }
    }

    public static Frame enter() {
        Frame previous = FRAME.get();
        if (GameServer.server) {
            FRAME.set(new Frame());
        }
        return previous;
    }

    public static void leave(Frame previous) {
        if (previous == null) {
            FRAME.remove();
        } else {
            FRAME.set(previous);
        }
    }

    public static int captureTicks() {
        int ticks = EntitySimulation.getSimulationTicksThisFrame();
        Frame frame = FRAME.get();
        if (frame == null || ticks <= 0) {
            return ticks;
        }

        frame.start = ticksSinceReset;
        ticksSinceReset += ticks;
        frame.end = ticksSinceReset;
        frame.elapsed = ticks;
        frame.effective = ticks;
        if (ticks > 1) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_CATCHUP_TICKS, ticks - 1);
        }
        return 1;
    }

    public static boolean active() {
        Frame frame = FRAME.get();
        return frame != null && frame.elapsed != 0;
    }

    public static void reset() {
        ticksSinceReset = 0;
        EntityDueIndex.reset();
        UsingPlayerIndex.reset();
    }

    public static boolean isDue(GameEntity entity, boolean valid) {
        Frame frame = FRAME.get();
        if (frame == null || frame.elapsed == 0 || !valid) {
            return valid;
        }
        if (entity.isMeta()) {
            long owed = owedTicks(entity.getEntityNetID(), frame.start, frame.end);
            if (owed <= 0) {
                OptimizationTelemetry.count(OptimizationTelemetry.Counter.ENTITY_META_NOT_DUE);
                return false;
            }
            frame.effective = (int) Math.min(owed, Integer.MAX_VALUE);
        } else {
            frame.effective = frame.elapsed;
        }
        return true;
    }

    private static long owedTicks(long phase, long start, long end) {
        return end - Math.floorMod(phase + end, (long) interval)
            - (start - Math.floorMod(phase + start, (long) interval));
    }

    static int interval() {
        return interval;
    }

    static int elapsedTicks() {
        Frame frame = FRAME.get();
        return frame == null ? 0 : frame.elapsed;
    }

    static long endTick() {
        Frame frame = FRAME.get();
        return frame == null ? 0 : frame.end;
    }

    public static int effectiveTicks() {
        Frame frame = FRAME.get();
        return frame == null || frame.effective == 0 ? 1 : frame.effective;
    }

    public static double gameSeconds() {
        return EntitySimulation.getGameSecondsPerTick() * effectiveTicks();
    }

    public static float scale(float amount) {
        return amount * effectiveTicks();
    }

    public static final class Frame {
        private int elapsed;
        private int effective;
        private long start;
        private long end;
    }
}
