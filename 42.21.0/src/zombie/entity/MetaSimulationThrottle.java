package zombie.entity;

public final class MetaSimulationThrottle {
    private static final int INTERVAL = Math.max(1, Integer.getInteger("apocbr.metaSimulationInterval", 10));

    private MetaSimulationThrottle() {
    }

    static int getInterval() {
        return INTERVAL;
    }

    public static boolean shouldSkip(GameEntity entity) {
        int ticksThisFrame = EntitySimulation.getSimulationTicksThisFrame();
        if (!entity.isMeta()) {
            EntitySimulation.setEffectiveSimulationTicksThisFrame(ticksThisFrame);
            return false;
        }

        long endTick = EntitySimulation.getTotalSimulationTicks();
        long startTick = endTick - ticksThisFrame;
        long phase = entity.getEntityNetID();
        long dueAtEnd = endTick - Math.floorMod(phase + endTick, (long)INTERVAL);
        long dueAtStart = startTick - Math.floorMod(phase + startTick, (long)INTERVAL);
        long owedTicks = dueAtEnd - dueAtStart;
        if (owedTicks <= 0L) {
            return true;
        }

        EntitySimulation.setEffectiveSimulationTicksThisFrame((int)Math.min(owedTicks, Integer.MAX_VALUE));
        return false;
    }
}
