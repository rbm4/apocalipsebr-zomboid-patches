package zombie.apocalipse.humans.brain;
/** A per-actor policy with pure eligibility. Only the selected module may actuate. */
public interface HumanModule extends HumanBehavior {
    /** Bounded passive intent sampling before arbitration. Must not actuate, claim bodies or cancel another module's actions. */
    default void monitor(HumanObservation observation,HumanActuator actuator) { }
    boolean eligible(HumanObservation observation);
    /** Cancellation on preemption must leave this module reusable. */
    void interrupt(long now, HumanActuator actuator);
}
