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
    private long nextPerception;
    private HumanObservation cached;

    ServerHumanActuator(ServerHuman human) { this.human = human; }

    HumanObservation observe(long now) {
        if(cached!=null&&now<nextPerception)return new HumanObservation(now,cached.visitors(),cached.canRoam());
        nextPerception=now+human.profile.perceptionIntervalMs()*1000000L;
        ArrayList<HumanObservation.Visitor> visitors = new ArrayList<>();
        for (IsoPlayer player : GameServer.Players) {
            if (eligible(player)) {
                double dx = player.getX() - human.player.getX();
                double dy = player.getY() - human.player.getY();
                double distance = dx * dx + dy * dy;
                if (distance <= radiusSquared() && visible(player)) {
                    visitors.add(new HumanObservation.Visitor(
                        new HumanObservation.Identity(player.getOnlineID(), player.getID()),
                        player.getX(), player.getY(), player.getZ(), distance));
                }
            }
        }
        boolean viewed = false;
        for (UdpConnection viewer : human.viewers) if (human.isRelevant(viewer)) { viewed = true; break; }
        visitors.sort(java.util.Comparator.comparingDouble(HumanObservation.Visitor::distanceSquared));
        cached=new HumanObservation(now,visitors.subList(0,Math.min(128,visitors.size())),viewed&&human.roaming.available());
        return cached;
    }

    private boolean eligible(IsoPlayer player) {
        if (player == null || player.isDead() || player.isInvisible()
            || player.getZi() != human.definition.z()
            || GameServer.IDToPlayerMap.get(player.getOnlineID()) != player) return false;
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        return connection != null && connection.isFullyConnected() && !GameServer.isDelayedDisconnect(player);
    }

    private double radiusSquared(){return human.profile.perceptionRadius()*human.profile.perceptionRadius();}
    private boolean visible(IsoPlayer player) {
        if(!human.profile.requireLos())return true;
        if (human.player.getCell().getGridSquare(human.player.getXi(), human.player.getYi(), human.definition.z()) == null
            || player.getCurrentSquare() == null) return false;
        LosUtil.TestResults result = LosUtil.lineClear(human.player.getCell(),
            human.player.getXi(), human.player.getYi(), human.definition.z(),
            player.getXi(), player.getYi(), player.getZi(), false);
        return result != LosUtil.TestResults.Blocked && result != LosUtil.TestResults.ClearThroughClosedDoor;
    }

    @Override
    public boolean face(HumanObservation.Identity identity) {
        IsoPlayer player = GameServer.IDToPlayerMap.get(identity.onlineId());
        if (!eligible(player) || player.getID() != identity.instanceId()) return false;
        float dx = player.getX() - human.player.getX();
        float dy = player.getY() - human.player.getY();
        if ((double)dx * dx + (double)dy * dy > radiusSquared() || !visible(player)) return false;
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
    public boolean say(String text) {
        return ServerHumanTransport.say(human, text);
    }

    @Override
    public boolean startRoaming(long now) { return human.roaming.start(now); }

    @Override
    public MotionStatus updateRoaming(long now) { return human.roaming.update(now); }

    @Override
    public void stopMoving() { human.roaming.stop(); }

    @Override
    public boolean returnHome() {
        stopEmote();
        // End the greeting at its last stopping point, without teleporting to spawn.
        human.player.setX(human.postX);
        human.player.setY(human.postY);
        human.player.setZ(human.definition.z());
        human.player.realx = human.postX;
        human.player.realy = human.postY;
        human.player.realz = (byte)human.definition.z();
        human.player.setForwardIsoDirection(human.definition.facing());
        return true;
    }
}
