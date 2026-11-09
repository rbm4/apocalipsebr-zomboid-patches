package zombie.apocalipse.humans;

import zombie.audio.parameters.ParameterBulletHitSurface;
import zombie.audio.parameters.ParameterMeleeHitSurface;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/** Native combat impact presentation, independent of optional speech/chance. */
final class ServerHumanCombatAudio {
    static void impact(ServerHuman human,IsoGameCharacter target,HandWeapon weapon){
        String sound=weapon.getZombieHitSound();if(sound==null||sound.isBlank())return;
        for(var viewer:human.viewers)if(human.isRelevant(viewer)){
            if(weapon.isRanged())INetworkPacket.send(viewer,PacketTypes.PacketType.PlaySound,sound,(byte)0,target,ParameterBulletHitSurface.Material.Flesh);
            else INetworkPacket.send(viewer,PacketTypes.PacketType.PlaySound,sound,(byte)0,human.player,ParameterMeleeHitSurface.Material.Body);
        }
    }
    private ServerHumanCombatAudio(){}
}
