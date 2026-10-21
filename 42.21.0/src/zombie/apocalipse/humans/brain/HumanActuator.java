package zombie.apocalipse.humans.brain;

/** Engine port. Its production implementation may only run on the simulation thread. */
public interface HumanActuator {
    enum MotionStatus { RUNNING, SUCCEEDED, FAILED }
    default boolean startRoaming(long now) { return false; }
    /** Advance the owned physical route, independently of the pattern that selected it. */
    default MotionStatus updateMovement(long now) { return MotionStatus.FAILED; }
    default void stopMoving() { }
    default HumanCombat.Port combat(){return HumanCombat.UNSUPPORTED;}
    boolean face(HumanObservation.Identity visitor);
    boolean startEmote(String name);
    void stopEmote();
    boolean say(String text);
    boolean returnHome();
}
