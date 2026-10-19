package zombie.apocalipse.humans;

import zombie.core.raknet.UdpConnection;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.PlayerPacketReliable;
import zombie.network.chat.ChatServer;

/** Only vanilla 42.21.0 packets cross the wire; clients need no new Java classes. */
final class ServerHumanTransport {
    private ServerHumanTransport() {
    }

    static void announce(ServerHuman human, UdpConnection connection) {
        GameServer.sendPlayerConnected(human.player, connection);
        human.viewers.add(connection);
        // Also clear a previously interrupted emote when a known client reloads.
        emote(human, connection, human.emote != null);
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

    static void emote(ServerHuman human, UdpConnection connection, boolean playing) {
        new ServerHumanEmotePacket(human, playing).sendToClient(PacketTypes.PacketType.State, connection);
    }

    static boolean say(ServerHuman human, String text) {
        boolean sent = false;
        for (UdpConnection connection : human.viewers) {
            if (!human.isRelevant(connection)) continue;
            // One chat packet per connection, including split-screen viewers.
            for (int slot = 0; slot < 4; slot++) {
                IsoPlayer listener = connection.getPlayerAt(slot);
                if (listener == null || listener.isDead() || listener.getZi() != human.definition.z()) continue;
                double dx = listener.getX() - human.definition.x();
                double dy = listener.getY() - human.definition.y();
                if (dx * dx + dy * dy > 30.0 * 30.0) continue;
                sent |= ChatServer.getInstance().sendHumanSay(listener.getOnlineID(), human.player.getUsername(), text);
                break;
            }
        }
        return sent;
    }
}
