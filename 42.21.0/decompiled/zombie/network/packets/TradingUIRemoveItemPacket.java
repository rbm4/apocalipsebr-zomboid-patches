// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.TradingManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.Trading;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIRemoveItemPacket extends Trading implements INetworkPacket {
    @JSONField
    protected int itemId;

    public void set(IsoPlayer you, IsoPlayer other, InventoryItem item) {
        super.set(you, other);
        this.itemId = item.getID();
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putInt(this.itemId);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.itemId = b.getInt();
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("TradingUIRemoveItem", this.playerA.getPlayer(), this.playerB.getPlayer(), this.itemId);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!connection.hasPlayer(this.playerA.getID())) {
            DebugType.Multiplayer.error("invalid player");
        } else {
            if (TradingManager.getInstance().removeItem(this.playerA.getPlayer(), this.itemId)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIRemoveItem, this.playerB.getPlayer().getUsername());
            }
        }
    }
}
