package zombie.apocalipse.humans;

import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.network.packets.hit.PlayerHitSquarePacket;

/** Native no-character-hit event: play the attack without processing a zombie reaction. */
final class ServerHumanMissPacket extends PlayerHitSquarePacket {
    ServerHumanMissPacket(IsoPlayer actor,HandWeapon weapon){set(actor,weapon,new zombie.CombatManager.TargetHitList(null,List.of()),List.of());}
    @Override public void attack(){ }
}
