package zombie.apocalipse.humans.brain;
/** A per-actor policy with pure eligibility. Only the selected module may actuate. */
public interface HumanModule extends HumanBehavior {
    boolean eligible(HumanObservation observation);
    /** Cancellation on preemption must leave this module reusable. */
    void interrupt(long now, HumanActuator actuator);
}
