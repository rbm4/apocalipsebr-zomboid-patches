// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.nio.ByteBuffer;
import zombie.characters.Capability;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.BodyDamageSync;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.character.OnlinePlayer;

@PacketSetting(ordering = 5, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class BodyDamageUpdatePacket implements INetworkPacket {
    @JSONField
    private BodyDamageUpdatePacket.Type packetType = BodyDamageUpdatePacket.Type.START_UPDATING;
    @JSONField
    private final ConnectionPlayer currentPlayer = new ConnectionPlayer();
    @JSONField
    private final OnlinePlayer remotePlayer = new OnlinePlayer();
    private ByteBuffer data;
    private ByteBufferReader dataReader;

    @Override
    public void setData(Object... values) {
        this.currentPlayer.set((IsoPlayer)values[0]);
        this.remotePlayer.set((IsoPlayer)values[1]);
        this.packetType = (BodyDamageUpdatePacket.Type)values[2];
        DebugType.Multiplayer.noise("%s from %s to %s", this.packetType.name(), this.remotePlayer.getDescription(), this.currentPlayer.getDescription());
    }

    public void setUpdate(IsoPlayer remotePlayer, IsoPlayer requester, ByteBuffer inputData) {
        this.packetType = BodyDamageUpdatePacket.Type.UPDATE;
        this.currentPlayer.set(requester);
        this.remotePlayer.set(remotePlayer);
        this.data = ByteBuffer.allocate(inputData.position());
        this.dataReader = new ByteBufferReader(this.data);
        this.data.put(inputData.array(), 0, inputData.position());
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putEnum(this.packetType);
        this.currentPlayer.write(b);
        this.remotePlayer.write(b);
        if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
            this.data.position(0);
            b.put(this.data);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.packetType = b.getEnum(BodyDamageUpdatePacket.Type.class);
        this.currentPlayer.parse(b, connection);
        this.remotePlayer.parse(b, connection);
        if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
            this.data = ByteBuffer.allocate(b.limit() - b.position());
            this.dataReader = new ByteBufferReader(this.data);
            this.data.position(0);
            this.data.put(b.bb);
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        if (this.packetType != BodyDamageUpdatePacket.Type.START_UPDATING) {
            if (this.packetType != BodyDamageUpdatePacket.Type.STOP_UPDATING) {
                if (this.packetType == BodyDamageUpdatePacket.Type.UPDATE) {
                    this.data.position(0);
                    BodyDamage bodyDamage = this.remotePlayer.getPlayer().getBodyDamageRemote();
                    byte bd = this.data.get();
                    if (bd == 50) {
                        bodyDamage.setOverallBodyHealth(this.data.getFloat());
                        bodyDamage.setRemotePainLevel(this.data.get());
                        bodyDamage.isFakeInfected = this.data.get() != 0;
                        this.remotePlayer.getPlayer().getStats().set(CharacterStat.ZOMBIE_INFECTION, this.data.getFloat());
                        bd = this.data.get();
                    }

                    while (bd == 64) {
                        int partIndex = this.data.get();
                        BodyPart part = bodyDamage.getBodyParts().get(partIndex);

                        for (byte id = this.data.get(); id != 65; id = this.data.get()) {
                            part.sync(this.dataReader, id);
                        }

                        bd = this.data.get();
                    }
                }
            }
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        switch (this.packetType) {
            case START_UPDATING:
                BodyDamageSync.instance.startSendingUpdates(this.remotePlayer.getId(), this.currentPlayer.getId());
                break;
            case STOP_UPDATING:
                BodyDamageSync.instance.stopSendingUpdates(this.remotePlayer.getId(), this.currentPlayer.getId());
            case UPDATE:
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return !this.currentPlayer.isConsistent(connection)
            ? false
            : this.packetType == BodyDamageUpdatePacket.Type.STOP_UPDATING || this.remotePlayer.isConsistent(connection);
    }

    public static enum Type {
        START_UPDATING,
        STOP_UPDATING,
        UPDATE;
    }
}
