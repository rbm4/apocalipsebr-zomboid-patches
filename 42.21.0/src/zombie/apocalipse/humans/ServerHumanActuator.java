package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.apocalipse.humans.brain.HumanObservation;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/** All live-world reads and writes stay on the factory's simulation thread. */
final class ServerHumanActuator implements HumanActuator {
    static final double VISITOR_RADIUS_SQUARED = 8.0 * 8.0;
    private final ServerHuman human;
    private final zombie.apocalipse.humans.survival.SurvivalPort survival;

    ServerHumanActuator(ServerHuman human) { this.human = human;this.survival=new ServerHumanSurvival(human); }
    @Override public zombie.apocalipse.humans.survival.SurvivalPort survival(){return survival;}
    @Override public void moduleChanged(String previous,String selected){
        HumanLog.log(HumanLog.Level.DEBUG,()->"module change key="+human.definition.key()+"; previous="+previous+"; selected="+selected);
    }
    @Override public zombie.apocalipse.humans.brain.HumanCombat.Port combat(){return human.combat;}

    HumanObservation observe(long now) {
        return human.perception.observe(now);
    }

    private boolean eligible(IsoPlayer player) {
        return human.perception.eligible(player);
    }

    private double radiusSquared(){return human.profile.perceptionRadius()*human.profile.perceptionRadius();}
    private boolean visible(IsoPlayer player) {
        return human.perception.visible(player);
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
    public boolean startMovement(zombie.apocalipse.humans.walking.MovementRequest request, long now) { return human.walking.start(request, now); }

    @Override
    public MotionStatus updateMovement(long now) { return human.walking.update(now); }

    @Override
    public void stopMoving() { human.walking.stop(); }

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
