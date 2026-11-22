package zombie.apocalipse.zombies;

import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;

/** Simulation-thread boundary into the logical NPC owner. No client damage requests. */
public interface ZombieVictimPort {
    record Victim(short id, IsoPlayer body, float x, float y, int z) { }
    List<Victim> snapshot();
    Victim resolve(short id);
    String strike(Victim expected, IsoZombie attacker, long execution);
}
