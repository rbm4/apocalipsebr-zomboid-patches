// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.TradingRequests;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class RequestTradingPacket extends PlayerRequestPacket {
    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("RequestTrade", this.sender.getPlayer(), this.destination.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        TradingRequests.getInstance().request(this.sender.getPlayer(), this.destination.getPlayer());
        this.sendToClient(PacketTypes.PacketType.RequestTrading, this.destination.getPlayer());
    }
}
