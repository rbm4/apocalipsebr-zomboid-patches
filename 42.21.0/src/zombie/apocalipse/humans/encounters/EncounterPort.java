package zombie.apocalipse.humans.encounters;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanObservation.Identity;

/** Simulation-thread sensor/semantic-memory port; native objects never enter policy. */
public interface EncounterPort {
    record Player(Identity identity,HumanRelationships.Key account,double distanceSquared,boolean armed,boolean aimingAt) { }
    List<Player> players();
    HumanRelationships.State relationship(Player player);
    long wallTimeMs();
    boolean claim(Player player,Object owner,long now);
    void release(Player player,Object owner);
    boolean record(Player player,HumanRelationships.Outcome outcome,EncounterPolicy policy);
    String text(String event);
}
