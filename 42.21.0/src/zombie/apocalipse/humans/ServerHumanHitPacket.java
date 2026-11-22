package zombie.apocalipse.humans;

import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.WeaponHit;
import zombie.network.packets.hit.PlayerHitZombiePacket;

/** Native wire packet; the server has already committed ammunition through its inventory port. */
final class ServerHumanHitPacket extends PlayerHitZombiePacket {
    ServerHumanHitPacket(IsoPlayer actor,HandWeapon weapon,IsoZombie target,List<WeaponHit> hits){
        set(actor,weapon,actor.isCriticalHit(),List.of(),hits,target,false);
        // No locally simulated fall trajectory exists on this synthetic attacker.
        fall.set(0,0,(byte)0,0);
    }
    // Native server attack() would fire OnWeaponSwingHitPoint and spend a second round.
    // Clients decode the ordinary PlayerHitZombie class and retain its normal attack presentation.
    @Override public void attack(){ }
}
