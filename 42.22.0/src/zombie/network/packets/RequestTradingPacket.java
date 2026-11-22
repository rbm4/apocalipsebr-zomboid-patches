// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.core.network.ByteBufferReader;
import zombie.network.IConnection;
import zombie.apocalipse.humans.ServerHumanTrading;
import zombie.characters.Capability;
import zombie.characters.TradingRequests;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class RequestTradingPacket extends PlayerRequestPacket {
    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        ServerHumanTrading.resolveTarget(this.destination);
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("RequestTrade", this.sender.getPlayer(), this.destination.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (ServerHumanTrading.request(this.sender.getPlayer(), this.destination.getPlayer(), connection, true)) return;
        TradingRequests.getInstance().request(this.sender.getPlayer(), this.destination.getPlayer());
        this.sendToClient(PacketTypes.PacketType.RequestTrading, this.destination.getPlayer());
    }
}
