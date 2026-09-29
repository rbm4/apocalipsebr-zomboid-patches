// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.anticheats;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.fields.hit.Hit;
import zombie.network.packets.INetworkPacket;

public class AntiCheatHitDamage extends AbstractAntiCheat {
    private static final int MAX_DAMAGE = 100;

    @Override
    public String validate(UdpConnection connection, INetworkPacket packet) {
        String result = super.validate(connection, packet);
        if (packet instanceof AntiCheatHitDamage.IAntiCheat field) {
            int hitCount = field.getHitCount();

            for (int i = 0; i < hitCount; i++) {
                float damage = field.getHit(i).getDamage();
                if (damage > 100.0F) {
                    return String.format("damage=%f is too big", damage);
                }
            }

            return result;
        } else {
            DebugType.Multiplayer.error("Invalid packet-type=%s for anti-cheat=%s", packet.getClass().getSimpleName(), this.getClass().getSimpleName());
            return "";
        }
    }

    public interface IAntiCheat {
        Hit getHit(int var1);

        default int getHitCount() {
            return 1;
        }
    }
}
