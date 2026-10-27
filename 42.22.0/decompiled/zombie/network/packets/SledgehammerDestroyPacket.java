// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.ServerOptions;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.UseDebugContextMenu, handlingType = 3)
public class SledgehammerDestroyPacket extends RemoveItemFromSquarePacket implements INetworkPacket {
    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!ServerOptions.instance.allowDestructionBySledgehammer.getValue()) {
            DebugType.Multiplayer.error("destruction by sledgehammer is not allowed on the server");
        } else {
            removeItemFromMap(connection, this.x, this.y, this.z, this.index);
            this.sendToRelativeClients(PacketTypes.PacketType.SledgehammerDestroy, null, this.x, this.y);
        }
    }
}
