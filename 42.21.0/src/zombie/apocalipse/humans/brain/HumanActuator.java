package zombie.apocalipse.humans.brain;

/** Engine port. Its production implementation may only run on the simulation thread. */
public interface HumanActuator {
    default zombie.apocalipse.humans.tasks.HumanTaskWorld taskWorld(){return zombie.apocalipse.humans.tasks.HumanTaskWorld.UNSUPPORTED;}
    enum MotionStatus { RUNNING, SUCCEEDED, FAILED }
    default boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest request, long now) { return false; }
    /** Advance the owned physical route, independently of the pattern that selected it. */
    default MotionStatus updateMovement(long now) { return MotionStatus.FAILED; }
    default void stopMoving() { }
    default HumanCombat.Port combat(){return HumanCombat.UNSUPPORTED;}
    default zombie.apocalipse.humans.survival.SurvivalPort survival(){return zombie.apocalipse.humans.survival.SurvivalPort.UNSUPPORTED;}
    default void moduleChanged(String previous,String selected){ }
    default void tactical(TacticalIntent intent){ }
    boolean face(HumanObservation.Identity visitor);
    boolean startEmote(String name);
    void stopEmote();
    boolean say(String text);
    boolean returnHome();
}
