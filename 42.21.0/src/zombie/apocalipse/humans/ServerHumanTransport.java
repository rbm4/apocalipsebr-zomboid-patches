package zombie.apocalipse.humans;

import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacketReliable;

/** Only vanilla 42.21.0 packets cross the wire; clients need no new Java classes. */
final class ServerHumanTransport {
    private ServerHumanTransport() {
    }

    static void announce(ServerHuman human, UdpConnection connection) {
        GameServer.sendPlayerConnected(human.player, connection);
        human.viewers.add(connection);
    }

    static void idle(ServerHuman human, UdpConnection connection) {
        PlayerPacketReliable packet = new PlayerPacketReliable();
        packet.id.set(human.player);
        packet.id.setPlayerIndex((byte)-1);
        // Match NetworkPlayerAI.setStatic(), without the client-owned AI send timer.
        packet.prediction.type = 0;
        packet.prediction.x = human.definition.x();
        packet.prediction.y = human.definition.y();
        packet.prediction.z = (byte)human.definition.z();
        packet.prediction.direction = human.player.getDirectionAngleRadians();
        packet.prediction.position.set(packet.prediction.x, packet.prediction.y, packet.prediction.z);
        packet.hitVehicleId.set(null);
        // All movement/action flags stay clear. God mode travels in ConnectedPlayer.
        packet.booleanVariables = 0;
        packet.disconnected = false;
        packet.variables.set(human.player);
        packet.sendToClient(PacketTypes.PacketType.PlayerUpdateReliable, connection);
    }

    static void remove(ServerHuman human, UdpConnection connection) {
        INetworkPacket.send(connection, PacketTypes.PacketType.PlayerTimeout, human.player);
    }
}
