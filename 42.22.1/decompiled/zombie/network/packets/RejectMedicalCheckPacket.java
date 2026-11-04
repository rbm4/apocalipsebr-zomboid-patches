// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.characters.Capability;
import zombie.characters.MedicalRequests;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class RejectMedicalCheckPacket extends PlayerRequestPacket {
    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        MedicalRequests.getInstance().response(this.destination.getPlayer(), this.sender.getPlayer());
    }
}
