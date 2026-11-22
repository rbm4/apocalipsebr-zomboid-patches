// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.io.IOException;
import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.TradingManager;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.inventory.InventoryItem;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIAddItemPacket extends PlayerRequestPacket {
    private static final String INVALID_ITEM = "invalid item";
    @JSONField
    private int itemId;
    private InventoryItem item;

    @Override
    public void setData(Object... values) {
        super.setData(values);
        this.itemId = (Integer)values[2];
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        if (GameClient.client) {
            b.putInt(this.itemId);
        } else {
            try {
                this.item.saveWithSize(b.bb, false);
            } catch (IOException var3) {
                DebugType.General.printException(var3, LogSeverity.Error);
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        zombie.apocalipse.humans.ServerHumanTrading.resolveTarget(this.destination);
        this.item = null;
        if (GameServer.server) {
            this.itemId = b.getInt();
            IsoPlayer isoPlayer = this.sender.getPlayer();
            if (isoPlayer != null) {
                this.item = isoPlayer.getInventory().getItemWithID(this.itemId);
            }
        } else {
            try {
                this.item = InventoryItem.loadItem(b.bb, 251);
            } catch (Exception var4) {
                DebugType.General.printException(var4, LogSeverity.Error);
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!super.isConsistent(connection)) {
            return false;
        } else if (this.item == null) {
            DebugType.Multiplayer.error("invalid item");
            return false;
        } else {
            return true;
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("TradingUIAddItem", this.sender.getPlayer(), this.destination.getPlayer(), this.item);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (zombie.apocalipse.humans.ServerHumanTrading.addItem(
            this.sender.getPlayer(), this.destination.getPlayer(), connection, this.item
        )) return;
        if (!TradingManager.getInstance().isInvalid(this.sender.getPlayer(), this.destination.getPlayer())) {
            if (TradingManager.getInstance().addItem(this.sender.getPlayer(), this.item)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIAddItem, this.destination.getPlayer());
            }
        }
    }
}
