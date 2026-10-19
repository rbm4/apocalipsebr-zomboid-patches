package zombie.apocalipse.humans.brain;

/** Engine port. Its production implementation may only run on the simulation thread. */
public interface HumanActuator {
    boolean face(HumanObservation.Identity visitor);
    boolean startEmote(String name);
    void stopEmote();
    boolean say(String text);
    boolean returnHome();
}
