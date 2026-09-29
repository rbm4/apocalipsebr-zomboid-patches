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
import zombie.network.fields.Trading;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIAddItemPacket extends Trading implements INetworkPacket {
    private static final String INVALID_ITEM = "invalid item";
    @JSONField
    private int itemId;
    private InventoryItem item;

    public void set(IsoPlayer you, IsoPlayer other, InventoryItem item) {
        super.set(you, other);
        this.itemId = item.getID();
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
        if (GameServer.server) {
            this.itemId = b.getInt();
            if (this.playerA.getPlayer() != null) {
                this.item = this.playerA.getPlayer().getInventory().getItemWithID(this.itemId);
            }
        } else {
            try {
                this.item = InventoryItem.loadItem(b.bb, 249);
            } catch (Exception var4) {
                DebugType.General.printException(var4, LogSeverity.Error);
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("TradingUIAddItem", this.playerA.getPlayer(), this.playerB.getPlayer(), this.item);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!connection.hasPlayer(this.playerA.getID())) {
            DebugType.Multiplayer.error("invalid player");
        } else if (this.item == null) {
            DebugType.Multiplayer.error("invalid item");
        } else {
            if (TradingManager.getInstance().addItem(this.playerA.getPlayer(), this.item)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIAddItem, this.playerB.getPlayer().getUsername());
            }
        }
    }
}
