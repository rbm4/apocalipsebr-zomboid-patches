// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.CharacterID;
import zombie.network.fields.hit.Fall;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 0, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3, anticheats = {})
public class ZombieStaggerPacket implements INetworkPacket {
    @JSONField
    protected final CharacterID target = new CharacterID();
    @JSONField
    protected final Fall fall = new Fall();

    @Override
    public void setData(Object... values) {
        this.set((IsoZombie)values[0], (Float)values[1], (Float)values[2], (Byte)values[3], (Float)values[4]);
    }

    public void set(IsoZombie target, float dropPositionX, float dropPositionY, byte dropPositionZ, float dropDirection) {
        this.target.set(target);
        this.fall.set(dropPositionX, dropPositionY, dropPositionZ, dropDirection);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.target.parse(b, connection);
        this.fall.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.target.write(b);
        this.fall.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.target.isConsistent(connection);
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoGameCharacter character = this.target.getCharacter();
        if (character != null && character.isRemote()) {
            this.fall.process(character);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.fall.process(this.target.getCharacter());
        this.sendToRelativeClients(PacketTypes.PacketType.ZombieStagger, connection, this.target.getX(), this.target.getY());
    }
}
