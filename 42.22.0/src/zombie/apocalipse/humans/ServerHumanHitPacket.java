package zombie.apocalipse.humans;

import java.util.List;
import zombie.CombatManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.Hit;
import zombie.network.packets.hit.PlayerHitZombiePacket;

/** Target-native child wire format; NPC inventory already commits ammunition. */
final class ServerHumanHitPacket extends PlayerHitZombiePacket {
    ServerHumanHitPacket(IsoPlayer actor,HandWeapon weapon,IsoZombie target,List<Hit> hits){
        set(actor,weapon,new CombatManager.TargetHitList(target,hits),List.of());
        fall.set(0,0,(byte)0,0);
    }
    @Override protected void processTarget(){
        if(zombie.network.GameServer.server)this.target.set(this.target.getZombie());
        else super.processTarget();
    }
    void refreshTarget(){this.target.set(this.target.getZombie());}
    @Override public void attack(){ }
}
