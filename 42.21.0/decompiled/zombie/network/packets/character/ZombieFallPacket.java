// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.id.ObjectIDType;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 0, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class ZombieFallPacket implements INetworkPacket {
    private static final short INVALID_ID = -1;
    @JSONField
    private short zombieId;
    @JSONField
    private boolean fallOnFront;

    @Override
    public void setData(Object... values) {
        IsoZombie zombie = (IsoZombie)values[0];
        this.zombieId = zombie.getOnlineID();
        this.fallOnFront = zombie.isFallOnFront();
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putShort(this.zombieId);
        b.putBoolean(this.fallOnFront);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.zombieId = b.getShort();
        this.fallOnFront = b.getBoolean();
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        for (Object deadBodyObject : ObjectIDType.DeadBody.getObjects()) {
            IsoDeadBody deadBody = (IsoDeadBody)deadBodyObject;
            if (deadBody.getCharacterOnlineID() == this.zombieId) {
                deadBody.setFallOnFront(this.fallOnFront);
                this.sendToRelativeClients(PacketTypes.PacketType.ZombieFall, null, deadBody.getX(), deadBody.getY());
                break;
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoZombie zombie = GameClient.IDToZombieMap.get(this.zombieId);
        if (zombie != null) {
            zombie.setFallOnFront(this.fallOnFront);
            zombie.getNetworkCharacterAI().setDeadBodyFallOnFront(this.fallOnFront);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.zombieId != -1;
    }
}
