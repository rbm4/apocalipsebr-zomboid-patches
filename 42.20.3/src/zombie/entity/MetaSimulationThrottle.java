package zombie.entity;

public final class MetaSimulationThrottle {
    private static final int INTERVAL = 10;

    private MetaSimulationThrottle() {
    }

    public static boolean shouldSkip(GameEntity entity) {
        if (!entity.isMeta()) {
            return false;
        }

        long tick = EntitySimulation.getCurrentTimeMillis() / EntitySimulation.getMillisPerTick();
        return Math.floorMod(entity.getEntityNetID() + tick, (long)INTERVAL) != 0L;
    }
}
