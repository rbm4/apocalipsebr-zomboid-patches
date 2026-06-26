// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import gnu.trove.iterator.TShortObjectIterator;
import gnu.trove.map.hash.TShortObjectHashMap;
import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitShortDistance;
import zombie.network.fields.character.ZombieID;
import zombie.network.fields.hit.Thumpable;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = AntiCheat.HitShortDistance
)
public class ZombieHitThumpablePacket implements HitCharacter, AntiCheatHitShortDistance.IAntiCheat {
    private final TShortObjectHashMap<IsoObject> thumpHitsToReceive = new TShortObjectHashMap<>();
    private TShortObjectHashMap<IsoObject> thumpHitsToSend;
    private final ZombieID zombieID = new ZombieID();
    private final Thumpable thumpable = new Thumpable();

    @Override
    public void setData(Object... values) {
        this.thumpHitsToSend = (TShortObjectHashMap<IsoObject>)values[0];
    }

    @Override
    public boolean isRelevant(UdpConnection connection) {
        TShortObjectIterator<IsoObject> iterator = this.thumpHitsToReceive.iterator();

        while (iterator.hasNext()) {
            iterator.advance();
            if (connection.isRelevantTo(iterator.value().getX(), iterator.value().getY())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putShort(this.thumpHitsToSend.size());
        TShortObjectIterator<IsoObject> iterator = this.thumpHitsToSend.iterator();

        while (iterator.hasNext()) {
            iterator.advance();
            b.putShort(iterator.key());
            this.thumpable.set(iterator.value());
            this.thumpable.write(b);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.thumpHitsToReceive.clear();
        int size = b.getShort();

        for (int i = 0; i < size; i++) {
            this.zombieID.parse(b, connection);
            if (this.zombieID.isConsistent(connection) && (!GameServer.server || this.zombieID.getZombie().getOwner() == connection)) {
                this.thumpable.parse(b, connection);
                if (this.thumpable.isConsistent(connection)) {
                    this.thumpable.process(this.zombieID.getZombie());
                    this.thumpHitsToReceive.put(this.zombieID.getID(), this.thumpable.getIsoObject());
                }
            }
        }

        if (GameServer.server) {
            this.thumpHitsToSend = this.thumpHitsToReceive;
            this.sendToClient(PacketTypes.PacketType.ZombieHitThumpable, connection);
        }
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.thumpable.getX(), this.thumpable.getY(), this.zombieID.getX(), this.zombieID.getY());
    }
}
