// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.anticheats;

import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.packets.INetworkPacket;

public class AntiCheatVehicleEnter extends AbstractAntiCheat {
    private static final float MAX_DISTANCE_TILES = 7.0F;

    @Override
    public String validate(UdpConnection connection, INetworkPacket packet) {
        String result = super.validate(connection, packet);
        if (packet instanceof AntiCheatVehicleEnter.IAntiCheat field) {
            float distance = field.getDistance();
            return distance > 7.0F ? String.format("distance=%f > max=%f", distance, 7.0F) : result;
        } else {
            DebugType.Multiplayer.error("Invalid packet-type=%s for anti-cheat=%s", packet.getClass().getSimpleName(), this.getClass().getSimpleName());
            return "";
        }
    }

    public interface IAntiCheat {
        float getDistance();
    }
}
