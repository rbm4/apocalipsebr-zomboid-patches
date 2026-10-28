package zombie.apocalipse.humans;

import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.packets.hit.PlayerHitPlayerPacket;

/** Ordinary PvP wire contract; ammo is committed by the NPC inventory, damage by native server processing. */
final class ServerHumanPlayerHitPacket extends PlayerHitPlayerPacket {
    ServerHumanPlayerHitPacket(IsoPlayer actor,HandWeapon weapon,IsoPlayer target,List<WeaponHit> hits){
        set(actor,weapon,actor.isCriticalHit(),List.of(),hits,target);
        fall.set(0,0,(byte)0,0);
    }
    @Override public void attack(){ }
}
