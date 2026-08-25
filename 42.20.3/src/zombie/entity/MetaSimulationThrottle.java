package zombie.entity;

/**
 * ApocBR: phase throttle for meta (unloaded / off-screen) entities.
 *
 * <p>Meta entities are simulated on one tick out of every {@link #INTERVAL}, spread across
 * the interval by entity net id so the cost of a broad bucket scan is amortized instead of
 * paid in full every simulation tick.
 *
 * <p>Being cheap is not enough on its own: skipping 9 of every 10 ticks without telling the
 * caller means meta crafting, drying, mashing, fluid and resource decay simply lose 90% of
 * their simulated time. So when an entity does run, this class also publishes how many ticks
 * that entity is accountable for via
 * {@link EntitySimulation#getEffectiveSimulationTicksThisFrame()} /
 * {@link EntitySimulation#getEffectiveGameSecondsThisFrame()}, and the systems use that as a
 * multiplier. Total simulated time is therefore conserved: the throttle only changes
 * <em>when</em> the work happens, never <em>how much</em> of it happens.
 *
 * <p>The accounting is stateless and O(1). For a monotonic tick index {@code T}, the last
 * tick at which an entity was due is {@code D(T) = T - floorMod(netId + T, INTERVAL)}.
 * {@code D} is non-decreasing, so {@code D(endTick) - D(startTick)} is exactly the number of
 * ticks that have come due for this entity since the previous frame - zero when it is not its
 * turn, {@code INTERVAL} in the steady state, and a multiple of {@code INTERVAL} when the
 * server lagged and a single frame consumed a large tick debt. Ticks after the last due tick
 * are not dropped, they are carried into the next run, so nothing is double counted or lost.
 */
public final class MetaSimulationThrottle {
    private static final int INTERVAL = Math.max(1, Integer.getInteger("apocbr.metaSimulationInterval", 10));

    private MetaSimulationThrottle() {
    }

    /**
     * Decides whether the given entity should be skipped this simulation pass and, when it is
     * not skipped, sets the effective tick count the caller must scale its work by. Callers
     * must read the effective tick count <em>after</em> this call, per entity, not hoisted out
     * of the loop.
     */
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

        EntitySimulation.setEffectiveSimulationTicksThisFrame((int)Math.min(owedTicks, 2147483647L));
        return false;
    }
}
