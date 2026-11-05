// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.MedicalRequests;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class RequestMedicalCheckPacket extends PlayerRequestPacket {
    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("RequestMedicalCheck", this.destination.getPlayer(), this.sender.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!MedicalRequests.isCheckForbidden(this.destination.getPlayer(), this.sender.getPlayer())) {
            MedicalRequests.getInstance().request(this.sender.getPlayer(), this.destination.getPlayer());
            this.sendToClient(PacketTypes.PacketType.RequestMedicalCheck, this.destination.getPlayer());
        }
    }
}
