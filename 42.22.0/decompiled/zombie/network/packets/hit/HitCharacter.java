// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoObject;
import zombie.network.PacketTypes;
import zombie.network.fields.IPositional;
import zombie.network.packets.INetworkPacket;

public interface HitCharacter extends INetworkPacket, IPositional {
    IsoObject getTarget();

    default void attack() {
    }

    default void react() {
    }

    default void update() {
    }

    default void preProcess() {
    }

    default void process() {
    }

    default void postProcess() {
    }

    default void log(UdpConnection connection) {
    }

    @Override
    default void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.log(connection);
        this.update();
        if (!(this instanceof PlayerHit)) {
            this.sendToRelativeClients(packetType, connection, this.getX(), this.getY());
        }

        this.processClient(connection);
    }

    @Override
    default void processClient(UdpConnection connection) {
        this.preProcess();
        this.process();
        this.postProcess();
        this.attack();
        this.react();
    }
}
