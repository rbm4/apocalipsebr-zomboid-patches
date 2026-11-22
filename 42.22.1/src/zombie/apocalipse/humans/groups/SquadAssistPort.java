package zombie.apocalipse.humans.groups;

import zombie.apocalipse.humans.brain.HumanActuator.MotionStatus;

/** World-thread observations; the existing serialized simulation movement adapter owns actuation. */
public interface SquadAssistPort {
    record Order(long sampledAt,SquadCombatCoordinator.Report report) { }
    Order observe();
    boolean approach(Order order,long now);
    MotionStatus updateApproach(long now);
    void stopApproach();
    default void transition(String phase,Order order) { }
    /** Renewed only while this execution owns an actual approach or combat session. */
    default void pursuit(Order order,long now,boolean active) { }
}
