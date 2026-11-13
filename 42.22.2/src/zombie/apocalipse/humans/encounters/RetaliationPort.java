package zombie.apocalipse.humans.encounters;

import zombie.apocalipse.humans.brain.HumanCombat;
import zombie.apocalipse.humans.brain.HumanObservation.Identity;

/** Detached target facts and a validated weapon execution. No live player enters policy. */
public interface RetaliationPort {
    record Contact(boolean valid,boolean visible) { }
    Contact observe(Identity identity);
    HumanCombat.Execution begin(Identity identity,long now);
}
