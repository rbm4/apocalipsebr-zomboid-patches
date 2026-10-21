package zombie.apocalipse.humans;

import zombie.core.raknet.UdpConnection;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerVariables;
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
        human.movementPresentation.remove(connection);
        movementPresentation(human, connection, human.movement.snapshot());
        // Also clear a previously interrupted emote when a known client reloads.
        emote(human, connection, human.emote != null);
    }

    static void idle(ServerHuman human, UdpConnection connection) {
        PlayerPacketReliable packet = new PlayerPacketReliable();
        packet.id.set(human.player);
        packet.id.setPlayerIndex((byte)-1);
        // Native prediction, clipped to a collision-checked straight segment.
        packet.prediction.type = 0;
        packet.prediction.x = human.player.getX();
        packet.prediction.y = human.player.getY();
        packet.prediction.z = (byte)human.player.getZi();
        packet.prediction.direction = human.player.getDirectionAngleRadians();
        ServerHumanMovement.Snapshot motion = human.movement.snapshot();
        movementPresentation(human, connection, motion);
        int predictionUnits = motion.predictionUnits();
        if (predictionUnits > 0) {
            packet.prediction.type = 1;
            packet.prediction.moveDirection = packet.prediction.direction;
            packet.prediction.speed = motion.speed();
            packet.prediction.distance = (byte)predictionUnits;
        }
        packet.prediction.position.set(packet.prediction.x, packet.prediction.y, packet.prediction.z);
        packet.hitVehicleId.set(null);
        // The native movement flag drives client walking; god mode travels in ConnectedPlayer.
        packet.booleanVariables = motion.moving()
            ? (short)(1 << NetworkPlayerVariables.Flags.hasDeferredMovement.ordinal()) : 0;
        if (motion.gait() == ServerHumanMovement.Gait.RUN)
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isRunning.ordinal());
        if (motion.gait() == ServerHumanMovement.Gait.SPRINT)
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isSprinting.ordinal());
        if (motion.animation().sneaking())
            packet.booleanVariables |= (short)(1 << NetworkPlayerVariables.Flags.isSneaking.ordinal());
        packet.disconnected = false;
        packet.variables.set(human.player);
        packet.sendToClient(PacketTypes.PacketType.PlayerUpdateReliable, connection);
    }

    private static void movementPresentation(ServerHuman human, UdpConnection connection, ServerHumanMovement.Snapshot motion) {
        if (motion.animation().equals(human.movementPresentation.get(connection))) return;
        // Native injury packet supplies the cached walk/run modifiers used by remote-player animation.
        INetworkPacket.send(connection, PacketTypes.PacketType.PlayerInjuries, human.player);
        human.movementPresentation.put(connection, motion.animation());
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
                double dx = listener.getX() - human.player.getX();
                double dy = listener.getY() - human.player.getY();
                if (dx * dx + dy * dy > 30.0 * 30.0) continue;
                sent |= ChatServer.getInstance().sendHumanSay(listener.getOnlineID(), human.player.getUsername(), text);
                break;
            }
        }
        return sent;
    }
}
