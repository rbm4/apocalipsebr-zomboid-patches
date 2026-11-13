// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.io.IOException;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.logger.LoggerManager;
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
import zombie.network.fields.character.ConnectionPlayer;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class EquipPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    int primaryHand = -1;
    @JSONField
    int secondaryHand = -1;

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof IsoPlayer player) {
            this.player.set(player);
            this.primaryHand = player.getPrimaryHandItem() == null ? -1 : player.getPrimaryHandItem().getID();
            this.secondaryHand = player.getSecondaryHandItem() == null ? -1 : player.getSecondaryHandItem().getID();
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (GameClient.client) {
            b.putInt(this.primaryHand);
            b.putInt(this.secondaryHand);
        }

        if (GameServer.server) {
            if (isoPlayer.getPrimaryHandItem() == null) {
                b.putEnum(EquipPacket.HandStatus.Nothing);
            } else {
                b.putEnum(EquipPacket.HandStatus.ItemExist);

                try {
                    isoPlayer.getPrimaryHandItem().saveWithSize(b.bb, false);
                    if (b.putBoolean(isoPlayer.getPrimaryHandItem().getVisual() != null)) {
                        isoPlayer.getPrimaryHandItem().getVisual().save(b.bb);
                    }
                } catch (IOException var5) {
                    DebugType.Multiplayer.printException(var5, "Primary ItemVisual save failed", LogSeverity.Error);
                }
            }

            if (isoPlayer.getSecondaryHandItem() == null) {
                b.putEnum(EquipPacket.HandStatus.Nothing);
            } else if (isoPlayer.getSecondaryHandItem() == isoPlayer.getPrimaryHandItem()) {
                b.putEnum(EquipPacket.HandStatus.SameItemAsPrimary);
            } else {
                b.putEnum(EquipPacket.HandStatus.ItemExist);

                try {
                    isoPlayer.getSecondaryHandItem().saveWithSize(b.bb, false);
                    if (b.putBoolean(isoPlayer.getSecondaryHandItem().getVisual() != null)) {
                        isoPlayer.getSecondaryHandItem().getVisual().save(b.bb);
                    }
                } catch (IOException var4) {
                    DebugType.Multiplayer.printException(var4, "Primary ItemVisual save failed", LogSeverity.Error);
                }
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (GameServer.server) {
            this.primaryHand = b.getInt();
            this.secondaryHand = b.getInt();
        }

        if (GameClient.client) {
            if (isoPlayer == null) {
                return;
            }

            EquipPacket.HandStatus primaryHand = b.getEnum(EquipPacket.HandStatus.class);
            if (primaryHand == EquipPacket.HandStatus.Nothing) {
                isoPlayer.setPrimaryHandItem(null);
            } else {
                InventoryItem primaryHandItem = null;

                try {
                    primaryHandItem = InventoryItem.loadItem(b.bb, 251);
                    if (isoPlayer.isLocalPlayer()) {
                        primaryHandItem = isoPlayer.getInventory().getItemWithID(primaryHandItem.getID());
                    } else {
                        primaryHandItem.setContainer(isoPlayer.getInventory());
                    }

                    isoPlayer.setPrimaryHandItem(primaryHandItem);
                    byte hasVisual = b.getByte();
                    if (hasVisual == 1) {
                        primaryHandItem.getVisual().load(b.bb, 251);
                    }
                } catch (Exception var9) {
                    ExceptionLogger.logException(var9);
                }

                if (primaryHandItem == null) {
                    LoggerManager.getLogger("user").write(connection.getIDStr() + " equipped unknown item type");
                    return;
                }
            }

            EquipPacket.HandStatus secondaryHand = b.getEnum(EquipPacket.HandStatus.class);
            if (secondaryHand == EquipPacket.HandStatus.Nothing) {
                isoPlayer.setSecondaryHandItem(null);
            } else if (secondaryHand == EquipPacket.HandStatus.SameItemAsPrimary) {
                isoPlayer.setSecondaryHandItem(isoPlayer.getPrimaryHandItem());
            } else {
                InventoryItem secondaryHandItem = null;

                try {
                    secondaryHandItem = InventoryItem.loadItem(b.bb, 251);
                    if (isoPlayer.isLocalPlayer()) {
                        secondaryHandItem = isoPlayer.getInventory().getItemWithID(secondaryHandItem.getID());
                    } else {
                        secondaryHandItem.setContainer(isoPlayer.getInventory());
                    }

                    isoPlayer.setSecondaryHandItem(secondaryHandItem);
                    byte hasVisual = b.getByte();
                    if (hasVisual == 1) {
                        secondaryHandItem.getVisual().load(b.bb, 251);
                    }
                } catch (Exception var8) {
                    ExceptionLogger.logException(var8);
                }

                if (secondaryHandItem == null) {
                    LoggerManager.getLogger("user").write(connection.getIDStr() + " equipped unknown item type");
                }
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer.isLocalPlayer()) {
            this.player.set(isoPlayer);
            this.primaryHand = isoPlayer.getPrimaryHandItem() == null ? -1 : isoPlayer.getPrimaryHandItem().getID();
            this.secondaryHand = isoPlayer.getSecondaryHandItem() == null ? -1 : isoPlayer.getSecondaryHandItem().getID();
            this.sendToServer(PacketTypes.PacketType.Equip);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (this.primaryHand == -1) {
            isoPlayer.setPrimaryHandItem(null);
        } else {
            isoPlayer.setPrimaryHandItem(isoPlayer.getInventory().getItemWithID(this.primaryHand));
        }

        if (this.secondaryHand == -1) {
            isoPlayer.setSecondaryHandItem(null);
        } else if (this.secondaryHand == this.primaryHand) {
            isoPlayer.setSecondaryHandItem(isoPlayer.getPrimaryHandItem());
        } else {
            isoPlayer.setSecondaryHandItem(isoPlayer.getInventory().getItemWithID(this.secondaryHand));
        }

        this.sendToClients(PacketTypes.PacketType.Equip, connection);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.getPlayer() != null;
    }

    static enum HandStatus {
        Nothing,
        ItemExist,
        SameItemAsPrimary;
    }
}
