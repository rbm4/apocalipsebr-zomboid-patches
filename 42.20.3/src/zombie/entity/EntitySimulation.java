// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.entity;

import zombie.GameTime;

public class EntitySimulation {
    private static final long MILLIS_PER_TICK = 100L;
    private static final double SECONDS_PER_TICK = 0.1;
    private static long currentTimeMillis;
    private static int simulationTicksThisFrame;
    private static int effectiveSimulationTicksThisFrame;
    // ApocBR: monotonic count of simulation ticks consumed since world load. Derived from the
    // same elapsed/100 arithmetic as simulationTicksThisFrame, so it is exact rather than
    // reconstructed from currentTimeMillis (which carries a sub-tick remainder).
    // MetaSimulationThrottle uses it to work out how many ticks a phased entity owes.
    private static long totalSimulationTicks;
    private static long lastTimeStamp;

    public static long getMillisPerTick() {
        return 100L;
    }

    public static double secondsPerTick() {
        return 0.1;
    }

    public static long getCurrentTimeMillis() {
        return currentTimeMillis;
    }

    public static int getSimulationTicksThisFrame() {
        return simulationTicksThisFrame;
    }

    public static long getTotalSimulationTicks() {
        return totalSimulationTicks;
    }

    /**
     * Number of simulation ticks the entity currently being processed is responsible for.
     * Defaults to {@link #getSimulationTicksThisFrame()} and is narrowed per entity by
     * {@link MetaSimulationThrottle#shouldSkip(GameEntity)} for phased meta entities, which
     * run less often but must then account for every tick they skipped.
     */
    public static int getEffectiveSimulationTicksThisFrame() {
        return effectiveSimulationTicksThisFrame;
    }

    static void setEffectiveSimulationTicksThisFrame(int ticks) {
        effectiveSimulationTicksThisFrame = ticks;
    }

    public static double getGameSecondsPerTick() {
        return 2.4000000000000004;
    }

    public static double getEffectiveGameSecondsThisFrame() {
        return getGameSecondsPerTick() * (double)effectiveSimulationTicksThisFrame;
    }

    protected static void update() {
        long millisPassed = (long)(GameTime.instance.getTimeDelta() * 1000.0F);
        currentTimeMillis += millisPassed;
        long elapsed = currentTimeMillis - lastTimeStamp;
        if (elapsed >= 100L) {
            simulationTicksThisFrame = (int)(elapsed / 100L);
            totalSimulationTicks += simulationTicksThisFrame;
            lastTimeStamp = currentTimeMillis - (elapsed - simulationTicksThisFrame * 100L);
        } else {
            simulationTicksThisFrame = 0;
        }

        effectiveSimulationTicksThisFrame = simulationTicksThisFrame;
    }

    protected static void reset() {
        currentTimeMillis = 0L;
        simulationTicksThisFrame = 0;
        effectiveSimulationTicksThisFrame = 0;
        totalSimulationTicks = 0L;
        lastTimeStamp = 0L;
    }
}
