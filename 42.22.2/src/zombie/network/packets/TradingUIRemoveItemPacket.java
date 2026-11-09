// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.core.TradingManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIRemoveItemPacket extends PlayerRequestPacket {
    @JSONField
    protected int itemId;

    @Override
    public void setData(Object... values) {
        super.setData(values);
        this.itemId = (Integer)values[2];
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putInt(this.itemId);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        zombie.apocalipse.humans.ServerHumanTrading.resolveTarget(this.destination);
        this.itemId = b.getInt();
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("TradingUIRemoveItem", this.sender.getPlayer(), this.destination.getPlayer(), this.itemId);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (zombie.apocalipse.humans.ServerHumanTrading.removeItem(
            this.sender.getPlayer(), this.destination.getPlayer(), connection, this.itemId
        )) return;
        if (!TradingManager.getInstance().isInvalid(this.sender.getPlayer(), this.destination.getPlayer())) {
            if (TradingManager.getInstance().removeItem(this.sender.getPlayer(), this.itemId)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIRemoveItem, this.destination.getPlayer());
            }
        }
    }
}
