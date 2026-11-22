// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import gnu.trove.iterator.TShortObjectIterator;
import gnu.trove.map.hash.TShortObjectHashMap;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.Capability;
import zombie.characters.IsoZombie;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitShortDistance;
import zombie.network.fields.character.ZombieID;
import zombie.network.fields.hit.Thumpable;
import zombie.network.packets.INetworkPacket;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = AntiCheat.HitShortDistance
)
public class ZombieHitThumpablePacket implements INetworkPacket, AntiCheatHitShortDistance.IAntiCheat {
    private final TShortObjectHashMap<IsoObject> thumpHitsToReceive = new TShortObjectHashMap<>();
    private final List<ZombieHitThumpablePacket.ThumpHit> receivedThumpHits = new ArrayList<>();
    private TShortObjectHashMap<IsoObject> thumpHitsToSend;
    private final ZombieID zombieID = new ZombieID();
    private final Thumpable thumpable = new Thumpable();
    private float furthestDistance;

    @Override
    public void setData(Object... values) {
        this.thumpHitsToSend = (TShortObjectHashMap<IsoObject>)values[0];
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
        this.receivedThumpHits.clear();
        float maxDistSq = 0.0F;
        int size = b.getShort();

        for (int i = 0; i < size; i++) {
            this.zombieID.parse(b, connection);
            this.thumpable.parse(b, connection);
            if (this.zombieID.isConsistent(connection)) {
                IsoZombie zombie = this.zombieID.getZombie();
                if ((!GameServer.server || zombie.getOwner() == connection) && this.thumpable.isConsistent(connection)) {
                    this.thumpHitsToReceive.put(this.zombieID.getID(), this.thumpable.getIsoObject());
                    this.receivedThumpHits.add(new ZombieHitThumpablePacket.ThumpHit(zombie, this.thumpable.getIsoObject()));
                    float dx = this.thumpable.getX() - zombie.getX();
                    float dy = this.thumpable.getY() - zombie.getY();
                    float distSq = dx * dx + dy * dy;
                    if (distSq > maxDistSq) {
                        maxDistSq = distSq;
                    }
                }
            }
        }

        this.furthestDistance = (float)Math.sqrt(maxDistSq);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!this.thumpHitsToReceive.isEmpty()) {
            this.processThumpHits();
            this.thumpHitsToSend = this.thumpHitsToReceive;

            for (UdpConnection relevantConnection : GameServer.udpEngine.connections) {
                if (relevantConnection.isFullyConnected()
                    && (relevantConnection.getConnectedGUID() == connection.getConnectedGUID() || this.isRelevant(relevantConnection))) {
                    this.sendToClient(packetType, relevantConnection);
                }
            }
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.processThumpHits();
    }

    private void processThumpHits() {
        for (ZombieHitThumpablePacket.ThumpHit thumpHit : this.receivedThumpHits) {
            Thumpable.process(thumpHit.zombie(), thumpHit.isoObject());
        }
    }

    private boolean isRelevant(UdpConnection connection) {
        return !this.thumpHitsToReceive.forEachValue(isoObject -> !connection.isRelevantTo(isoObject.getX(), isoObject.getY()));
    }

    @Override
    public float getFurthestHitDistance() {
        return this.furthestDistance;
    }

    private record ThumpHit(IsoZombie zombie, IsoObject isoObject) {
    }
}
