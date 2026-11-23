package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.features.npc.brain.HumanActuator;
import cloud.apocalipse.pz.agent.features.npc.brain.HumanObservation;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/** All live-world reads and writes stay on the factory's simulation thread. */
final class ServerHumanActuator implements HumanActuator {
    @Override public boolean seated(){human.requireAuthority();return human.seating!=null&&human.seating.ownsPose();}
    @Override public void stopSeating(){human.requireAuthority();if(human.seating!=null)human.seating.finish();}
    @Override public cloud.apocalipse.pz.agent.features.npc.seating.HumanSeating seating(){return human.seatingPort();}
    static final double VISITOR_RADIUS_SQUARED = 8.0 * 8.0;
    private final ServerHuman human;
    private final ServerHumanCorpses corpses;
    @Override public cloud.apocalipse.pz.agent.features.npc.tasks.HumanCorpsePort corpses(){return corpses;}
    @Override public cloud.apocalipse.pz.agent.features.npc.tasks.HumanTaskWorld taskWorld(){return taskWorld;}
    private final cloud.apocalipse.pz.agent.features.npc.tasks.HumanTaskWorld taskWorld;
    private cloud.apocalipse.pz.agent.features.npc.brain.TacticalIntent intent=cloud.apocalipse.pz.agent.features.npc.brain.TacticalIntent.NONE;
    cloud.apocalipse.pz.agent.features.npc.brain.TacticalIntent intent(){return intent;}
    @Override public void tactical(cloud.apocalipse.pz.agent.features.npc.brain.TacticalIntent value){
        var previous=intent;intent=java.util.Objects.requireNonNull(value);
        var target=value.target()==null?null:human.perception.resolveZombie(value.target());
        boolean guarded=value.guarded()&&target!=null;
        human.movement.tactical(guarded?target.getX():null,guarded?target.getY():null,value.elevatedExposure());
        ServerHumanTransport.aim(human,guarded&&!human.movement.traversing());
        if(!previous.equals(value))HumanLog.log(HumanLog.Level.DEBUG,()->"tactical key="+human.definition.key()+"; objective="+value.objective()
            +"; posture="+value.posture()+"; target="+value.target()+"; elevated="+value.elevatedExposure());
    }
    private final cloud.apocalipse.pz.agent.features.npc.survival.SurvivalPort survival;

    ServerHumanActuator(ServerHuman human) { this.human = human;this.corpses=new ServerHumanCorpses(human);this.survival=new ServerHumanSurvival(human);this.taskWorld=new ServerHumanTasks(human); }
    @Override public cloud.apocalipse.pz.agent.features.npc.survival.SurvivalPort survival(){return survival;}
    @Override public void moduleChanged(String previous,String selected){
        HumanLog.log(HumanLog.Level.DEBUG,()->"module change key="+human.definition.key()+"; previous="+previous+"; selected="+selected);
    }
    @Override public cloud.apocalipse.pz.agent.features.npc.brain.HumanCombat.Port combat(){return human.combat;}

    void updateCorpseDrag(long now){corpses.tick(now);}
    void announceCorpseDrag(UdpConnection viewer){corpses.announce(viewer);}
    void closeCorpses(){corpses.close();}
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
        if(human.seating!=null&&human.seating.ownsPose())return true;
        if (dx != 0 || dy != 0) human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy, dx)));
        return true;
    }

    @Override
    public boolean startEmote(String name) {
        if(human.seating!=null&&human.seating.ownsPose())return false;
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
    public boolean startMovement(cloud.apocalipse.pz.agent.features.npc.walking.MovementRequest request, long now) { return human.walking.start(request, now); }

    @Override
    public MotionStatus updateMovement(long now) { return human.walking.update(now); }

    @Override
    public void stopMoving() { human.walking.stop(); }

    @Override
    public boolean returnHome() {
        if(seated())return true;
        if(!human.motionAllowed())return false;
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
