package zombie.apocalipse.humans.brain;

/** Per-NPC autonomous policy. Interaction/lifecycle ownership stays outside it. */
public interface HumanBehavior {
    default boolean needsPerception() { return true; }
    void update(HumanObservation observation, HumanActuator actuator);
    void suspend(long now, HumanActuator actuator);
    void resume(HumanObservation.Identity visitor, long now, HumanActuator actuator);
    void stop(long now, HumanActuator actuator);
    boolean available();
}
