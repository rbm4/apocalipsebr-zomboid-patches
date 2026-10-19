package zombie.apocalipse.humans.brain;

/** Engine port. Its production implementation may only run on the simulation thread. */
public interface HumanActuator {
    enum MotionStatus { RUNNING, SUCCEEDED, FAILED }
    default boolean startRoaming(long now) { return false; }
    default MotionStatus updateRoaming(long now) { return MotionStatus.FAILED; }
    default void stopMoving() { }
    boolean face(HumanObservation.Identity visitor);
    boolean startEmote(String name);
    void stopEmote();
    boolean say(String text);
    boolean returnHome();
}
