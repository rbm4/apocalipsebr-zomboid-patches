package zombie.apocalipse.humans;

import java.util.ArrayList;
import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.LosUtil;
import zombie.network.GameServer;

/** All live-world reads and writes stay on the factory's simulation thread. */
final class ServerHumanActuator implements HumanActuator {
    static final double VISITOR_RADIUS_SQUARED = 8.0 * 8.0;
    private final ServerHuman human;

    ServerHumanActuator(ServerHuman human) { this.human = human; }

    HumanObservation observe(long now) {
        ArrayList<HumanObservation.Visitor> visitors = new ArrayList<>();
        for (IsoPlayer player : GameServer.Players) {
            if (eligible(player)) {
                double dx = player.getX() - human.definition.x();
                double dy = player.getY() - human.definition.y();
                double distance = dx * dx + dy * dy;
                if (distance <= VISITOR_RADIUS_SQUARED && visible(player)) {
                    visitors.add(new HumanObservation.Visitor(
                        new HumanObservation.Identity(player.getOnlineID(), player.getID()),
                        player.getX(), player.getY(), player.getZ(), distance));
                }
            }
        }
        return new HumanObservation(now, visitors);
    }

    private boolean eligible(IsoPlayer player) {
        if (player == null || player.isDead() || player.isInvisible()
            || player.getZi() != human.definition.z()
            || GameServer.IDToPlayerMap.get(player.getOnlineID()) != player) return false;
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        return connection != null && connection.isFullyConnected() && !GameServer.isDelayedDisconnect(player);
    }

    private boolean visible(IsoPlayer player) {
        if (human.player.getCell().getGridSquare((int)human.definition.x(), (int)human.definition.y(), human.definition.z()) == null
            || player.getCurrentSquare() == null) return false;
        LosUtil.TestResults result = LosUtil.lineClear(human.player.getCell(),
            (int)human.definition.x(), (int)human.definition.y(), human.definition.z(),
            player.getXi(), player.getYi(), player.getZi(), false);
        return result != LosUtil.TestResults.Blocked && result != LosUtil.TestResults.ClearThroughClosedDoor;
    }

    @Override
    public boolean face(HumanObservation.Identity identity) {
        IsoPlayer player = GameServer.IDToPlayerMap.get(identity.onlineId());
        if (!eligible(player) || player.getID() != identity.instanceId()) return false;
        float dx = player.getX() - human.definition.x();
        float dy = player.getY() - human.definition.y();
        if ((double)dx * dx + (double)dy * dy > VISITOR_RADIUS_SQUARED || !visible(player)) return false;
        if (dx != 0 || dy != 0) human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy, dx)));
        return true;
    }

    @Override
    public boolean startEmote(String name) {
        human.emote = name;
        boolean sent = false;
        for (UdpConnection viewer : human.viewers) {
            if (human.isRelevant(viewer)) {
                ServerHumanTransport.emote(human, viewer, true);
                sent = true;
            }
        }
        return sent;
    }

    @Override
    public void stopEmote() {
        if (human.emote == null) return;
        for (UdpConnection viewer : human.viewers) ServerHumanTransport.emote(human, viewer, false);
        human.emote = null;
    }

    @Override
    public boolean say(String text) { return ServerHumanTransport.say(human, text); }

    @Override
    public boolean returnHome() {
        stopEmote();
        // This guide never leaves its post. Restore the exact anchor and orientation.
        human.player.setX(human.definition.x());
        human.player.setY(human.definition.y());
        human.player.setZ(human.definition.z());
        human.player.realx = human.definition.x();
        human.player.realy = human.definition.y();
        human.player.realz = (byte)human.definition.z();
        human.player.setForwardIsoDirection(human.definition.facing());
        return true;
    }
}
