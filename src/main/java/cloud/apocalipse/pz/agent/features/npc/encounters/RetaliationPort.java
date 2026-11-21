package cloud.apocalipse.pz.agent.features.npc.encounters;

import cloud.apocalipse.pz.agent.features.npc.brain.HumanCombat;
import cloud.apocalipse.pz.agent.features.npc.brain.HumanObservation.Identity;

/** Detached target facts and a validated weapon execution. No live player enters policy. */
public interface RetaliationPort {
    record Contact(boolean valid,boolean visible) { }
    Contact observe(Identity identity);
    HumanCombat.Execution begin(Identity identity,long now);
}
