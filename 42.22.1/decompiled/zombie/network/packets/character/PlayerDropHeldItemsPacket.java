// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.ThrownItemManager;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;
import zombie.util.flags.EnumByteFlags;
import zombie.util.flags.OrdinalByteFlag;
import zombie.util.flags.WritableEnumByteFlags;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public final class PlayerDropHeldItemsPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    final WritableEnumByteFlags<PlayerDropHeldItemsPacket.Flag> flags = WritableEnumByteFlags.allocEmpty(PlayerDropHeldItemsPacket.Flag.class);
    @JSONField
    int x;
    @JSONField
    int y;
    @JSONField
    int z;
    @JSONField
    int targetX;
    @JSONField
    int targetY;
    @JSONField
    int targetZ;

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
        this.x = (Integer)values[1];
        this.y = (Integer)values[2];
        this.z = (Integer)values[3];
        this.flags.copyFrom((EnumByteFlags)values[4]);
        if (this.flags.has(PlayerDropHeldItemsPacket.Flag.isThrow)) {
            this.targetX = (Integer)values[5];
            this.targetY = (Integer)values[6];
            this.targetZ = (Integer)values[7];
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.x = b.getInt();
        this.y = b.getInt();
        this.z = b.getByte();
        this.flags.setFlagsRaw(b.getByte());
        if (this.flags.has(PlayerDropHeldItemsPacket.Flag.isThrow)) {
            this.targetX = b.getInt();
            this.targetY = b.getInt();
            this.targetZ = b.getByte();
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putInt(this.x);
        b.putInt(this.y);
        b.putByte(this.z);
        b.putByte(this.flags.asByte());
        if (this.flags.has(PlayerDropHeldItemsPacket.Flag.isThrow)) {
            b.putInt(this.targetX);
            b.putInt(this.targetY);
            b.putByte(this.targetZ);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (this.flags.has(PlayerDropHeldItemsPacket.Flag.isThrow)) {
            ThrownItemManager.getInstance().throwHeldItem(isoPlayer, this.targetX, this.targetY, this.targetZ);
        } else {
            isoPlayer.dropHeldItemsServer(this.x, this.y, this.z, this.flags);
        }

        INetworkPacket.sendToRelative(PacketTypes.PacketType.Equip, this.x, this.y, isoPlayer);
    }

    public static enum Flag implements OrdinalByteFlag {
        dropPrimary,
        dropSecondary,
        isThrow;

        private Flag() {
            this.assertOrdinalValid();
        }
    }

    public static enum Flags {
        dropPrimary(PlayerDropHeldItemsPacket.Flag.dropPrimary),
        dropSecondary(PlayerDropHeldItemsPacket.Flag.dropSecondary),
        dropBothHeldItems(PlayerDropHeldItemsPacket.Flag.dropPrimary, PlayerDropHeldItemsPacket.Flag.dropSecondary),
        throwPrimaryItem(PlayerDropHeldItemsPacket.Flag.dropPrimary, PlayerDropHeldItemsPacket.Flag.isThrow);

        private final EnumByteFlags<PlayerDropHeldItemsPacket.Flag> flags;

        private Flags(final PlayerDropHeldItemsPacket.Flag firstFlag, final PlayerDropHeldItemsPacket.Flag... flags) {
            this.flags = EnumByteFlags.alloc(firstFlag, flags);
        }

        public EnumByteFlags<PlayerDropHeldItemsPacket.Flag> getFlags() {
            return this.flags;
        }
    }
}
