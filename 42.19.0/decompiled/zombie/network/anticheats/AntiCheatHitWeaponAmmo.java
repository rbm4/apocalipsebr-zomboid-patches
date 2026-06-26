// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.anticheats;

import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.packets.INetworkPacket;

public class AntiCheatHitWeaponAmmo extends AbstractAntiCheat {
    @Override
    public String validate(UdpConnection connection, INetworkPacket packet) {
        String result = super.validate(connection, packet);
        AntiCheatHitWeaponAmmo.IAntiCheat field = (AntiCheatHitWeaponAmmo.IAntiCheat)packet;
        HandWeapon weapon = field.getHandWeapon();
        if (weapon == null) {
            return result;
        } else {
            if (weapon.isAimedFirearm() && !field.isIgnoreDamage()) {
                IsoPlayer wielder = field.getWielder();
                int ammoCount = wielder.networkAi.getShotID() == field.getShotID()
                    ? wielder.networkAi.ammoBeforeShot
                    : weapon.getCurrentAmmoCount() + (weapon.isRoundChambered() ? 1 : 0);
                if (ammoCount <= 0 && !wielder.isUnlimitedAmmo()) {
                    return String.format("ammo=%d", ammoCount);
                }
            }

            return result;
        }
    }

    public interface IAntiCheat {
        HandWeapon getHandWeapon();

        IsoPlayer getWielder();

        boolean isIgnoreDamage();

        byte getShotID();
    }
}
