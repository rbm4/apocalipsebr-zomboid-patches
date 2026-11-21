package cloud.apocalipse.pz.agent.features.npc.brain;

/** Engine port. Its production implementation may only run on the simulation thread. */
public interface HumanActuator {
    default boolean seated(){return false;}
    default void stopSeating(){ }
    default cloud.apocalipse.pz.agent.features.npc.seating.HumanSeating seating(){return cloud.apocalipse.pz.agent.features.npc.seating.HumanSeating.UNSUPPORTED;}
    default cloud.apocalipse.pz.agent.features.npc.tasks.HumanCorpsePort corpses(){return cloud.apocalipse.pz.agent.features.npc.tasks.HumanCorpsePort.UNSUPPORTED;}
    default cloud.apocalipse.pz.agent.features.npc.tasks.HumanTaskWorld taskWorld(){return cloud.apocalipse.pz.agent.features.npc.tasks.HumanTaskWorld.UNSUPPORTED;}
    enum MotionStatus { RUNNING, SUCCEEDED, FAILED }
    default boolean startMovement(cloud.apocalipse.pz.agent.features.npc.walking.MovementRequest request, long now) { return false; }
    /** Advance the owned physical route, independently of the pattern that selected it. */
    default MotionStatus updateMovement(long now) { return MotionStatus.FAILED; }
    default void stopMoving() { }
    default HumanCombat.Port combat(){return HumanCombat.UNSUPPORTED;}
    default cloud.apocalipse.pz.agent.features.npc.survival.SurvivalPort survival(){return cloud.apocalipse.pz.agent.features.npc.survival.SurvivalPort.UNSUPPORTED;}
    default void moduleChanged(String previous,String selected){ }
    default void tactical(TacticalIntent intent){ }
    boolean face(HumanObservation.Identity visitor);
    boolean startEmote(String name);
    void stopEmote();
    boolean say(String text);
    boolean returnHome();
}
