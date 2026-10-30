// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.TradingRequests;
import zombie.core.TradingManager;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class AcceptTradingPacket extends PlayerRequestPacket {
    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("AcceptedTrade", this.sender.getPlayer(), this.destination.getPlayer(), true);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (TradingRequests.getInstance().response(this.destination.getPlayer(), this.sender.getPlayer())) {
            TradingManager.getInstance().addNewTrading(this.destination.getPlayer(), this.sender.getPlayer());
            this.sendToClient(PacketTypes.PacketType.AcceptTrading, this.destination.getPlayer());
        }
    }
}
