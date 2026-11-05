package zombie.apocalipse.humans;

import java.util.random.RandomGenerator;
import zombie.apocalipse.humans.death.*;
import zombie.apocalipse.humans.encounters.HumanRelationships;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItemFactory;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameServer;

/** Native death/corpse adapter; lifecycle scheduling and durable state remain in their owners. */
final class ServerHumanDeaths {
    private ServerHumanDeaths(){ }
    static HumanReputationPort reputation=event->HumanLog.log(HumanLog.Level.INFO,()->"NPC kill reputation hook; npc="+event.npc()+"; death="+event.deathId());
    static void died(ServerHuman human,HumanLifecycle logical){
        human.stopActivities();
        var killer=human.deathKiller;
        if(killer==null&&human.profile.encounter().enabled()&&human.player.getAttackedBy() instanceof IsoPlayer player
            &&GameServer.IDToPlayerMap.get(player.getOnlineID())==player)killer=ServerHumanEncounters.key(human,player);
        if(killer!=null) {
            var policy=human.profile.encounter();
            var event=new HumanReputationPort.Kill(logical.death().deathId(),human.definition.key(),logical.activations(),killer,logical.death().diedAtMs());
            // Default implementation uses the pinned profile policy and existing journal queue.
            if(!ServerHumanEncounters.memory().record(killer,human.definition.key(),HumanRelationships.Outcome.KILLED_NPC,event.occurredMs(),policy))
                HumanLog.log(HumanLog.Level.ERROR,()->"NPC kill relationship event could not be queued; npc="+human.definition.key());
            try{notifyReputation(event);}catch(RuntimeException failure){HumanLog.log(HumanLog.Level.ERROR,()->"NPC reputation extension failed; npc="+event.npc());}
        }
        human.inventory.releaseForCorpse();
        var drops=human.definition.deathPolicy().loot().roll(RandomGenerator.getDefault());
        var corpse=new IsoDeadBody(human.player);
        corpse.setReanimateTime(-1);
        corpse.getModData().rawset("apocbr.npc.key",human.definition.key());
        corpse.getModData().rawset("apocbr.npc.deathId",logical.death().deathId());
        for(var drop:drops)for(int n=0;n<drop.quantity();n++){
            var item=InventoryItemFactory.CreateItem(drop.item());
            if(item!=null)corpse.getContainer().AddItem(item);
            else HumanLog.log(HumanLog.Level.ERROR,()->"NPC corpse loot item unavailable: "+drop.item());
        }
        GameServer.sendCorpse(corpse);
    }
    private static void notifyReputation(HumanReputationPort.Kill event){reputation.npcKilled(event);}
}
