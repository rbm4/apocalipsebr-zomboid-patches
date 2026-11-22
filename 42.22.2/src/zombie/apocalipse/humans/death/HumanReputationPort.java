package zombie.apocalipse.humans.death;

import zombie.apocalipse.humans.encounters.HumanRelationships;

/** Future faction standing service implements this port; no numeric reputation policy is invented here. */
@FunctionalInterface
public interface HumanReputationPort {
    record Kill(String deathId,String npc,int incarnation,HumanRelationships.Key killer,long occurredMs) { }
    void npcKilled(Kill event);
}
