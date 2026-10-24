package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.tasks.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/** Native task queries and command preflight. All calls belong to the simulation thread. */
final class ServerHumanTasks implements HumanTaskWorld {
    private final ServerHuman human;
    ServerHumanTasks(ServerHuman human){this.human=human;}
    static AssignedTaskModule module(ServerHuman human){
        return human.activity.behavior() instanceof HumanModuleRuntime runtime?runtime.module(AssignedTaskModule.class):null;
    }
    static boolean accepts(ServerHuman human,HumanTaskCommand command,IsoPlayer issuer){
        var module=module(human);
        if(module==null||!module.available())return false;
        var world=human.actuator.taskWorld();
        return switch(command.kind()) {
            case CLEAR_CORPSES -> {
                var source=command.source()!=null?command.source():world.position();
                var corpses=human.actuator.corpses();
                yield corpses.supported()&&source.z()==command.destination().z()&&corpses.permitted(source)&&corpses.permitted(command.destination());
            }
            case MOVE_TO -> world.permitted(command.destination());
            case FOLLOW_PLAYER -> {
                var point=world.locate(new HumanObservation.Identity(issuer.getOnlineID(),issuer.getID()));
                yield point!=null&&world.approach(point,world.position(),command.distanceTiles())!=null;
            }
            default -> true;
        };
    }
    static void apply(ServerHuman human,HumanTaskCommand command,IsoPlayer issuer){
        module(human).assign(command,new HumanObservation.Identity(issuer.getOnlineID(),issuer.getID()),human.actuator.taskWorld().position());
        HumanLog.log(HumanLog.Level.DEBUG,()->"task accepted key="+human.definition.key()+"; kind="+command.kind());
    }
    public Point position(){return new Point(human.player.getXi(),human.player.getYi(),human.player.getZi());}
    public Point locate(HumanObservation.Identity target){
        var player=target==null?null:GameServer.IDToPlayerMap.get(target.onlineId());
        if(player==null||player.getID()!=target.instanceId()||player.isDead())return null;
        var connection=GameServer.getConnectionFromPlayer(player);
        if(connection==null||!connection.isFullyConnected())return null;
        return new Point(player.getXi(),player.getYi(),player.getZi());
    }
    public boolean permitted(Point destination){
        return destination.z()==human.player.getZi()&&human.walking.permitted(new Tile(destination.x(),destination.y()));
    }
    public void changed(AssignedTaskModule.Snapshot snapshot){
        HumanLog.log(HumanLog.Level.DEBUG,()->"task key="+human.definition.key()+"; kind="+snapshot.command().kind()+"; status="+snapshot.status()+"; reason="+snapshot.reason());
    }
}
