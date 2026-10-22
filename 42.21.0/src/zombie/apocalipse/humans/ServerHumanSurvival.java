package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanSenses.Contact;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.movement.MovementModifiers.Pace;
import zombie.apocalipse.humans.survival.*;

/** Simulation-thread adapter. Profile territory and shared physical traversal apply to emergency routes too. */
final class ServerHumanSurvival implements SurvivalPort {
    private final ServerHuman human;
    private final RouteRefusals refusals=new RouteRefusals();
    private float progressX,progressY;
    private long lastProgress;
    ServerHumanSurvival(ServerHuman human){this.human=human;}
    public boolean escape(List<Contact> threats,SurvivalSettings settings,long now){
        human.walking.stop();
        var scope=human.walking.scope("background");if(scope==null||!scope.available())return false;
        var positions=threats.stream().filter(c->(int)Math.floor(c.z())==human.player.getZi())
            .map(c->new RetreatPlanner.Threat(c.x(),c.y())).toList();
        var start=new Tile(human.player.getXi(),human.player.getYi());
        var planning=new zombie.apocalipse.humans.movement.MovementGrid(){
            public boolean available(){return scope.available();}
            public boolean walkable(Tile tile){var square=human.movement.square(tile.x(),tile.y());
                return scope.walkable(tile)&&square!=null&&(tile.equals(start)||square.isFree(true));}
            public boolean connected(Tile from,Tile to){return traversalCost(from,to)>0;}
            public int traversalCost(Tile from,Tile to){return refusals.blocked(from,to,now)?0:scope.traversalCost(from,to);}
        };
        var route=RetreatPlanner.plan(planning,start,positions,settings.retreatDistance());
        if(route.size()<2){transition("CORNERED","no_reachable_escape",threats.size());return false;}
        progressX=human.player.getX();progressY=human.player.getY();lastProgress=now;
        boolean started=human.movement.start(route,scope,Pace.RUN,now);
        if(started)HumanLog.log(HumanLog.Level.DEBUG,()->"retreat route key="+human.definition.key()+"; from="+start
            +"; goal="+route.getLast()+"; steps="+(route.size()-1)+"; threats="+positions.size());
        return started;
    }
    public void failed(boolean timedOut,long now){
        var failure=human.movement.lastFailure();
        var from=failure==null?new Tile(human.player.getXi(),human.player.getYi()):failure.from();var to=failure==null?human.movement.nextTile():failure.to();
        if(from!=null&&to!=null&&!from.equals(to))refusals.refuse(from,to,now,timedOut||failure!=null&&failure.reason().equals("body_occupied"));
        if(timedOut)transition("CORNERED","movement_deadline",0);
    }
    public String routeProblem(long now){
        if(human.movement.traversing()||Math.hypot(human.player.getX()-progressX,human.player.getY()-progressY)>=.2){
            progressX=human.player.getX();progressY=human.player.getY();lastProgress=now;return null;
        }
        if(now-lastProgress<1_500_000_000L)return null;
        transition("CORNERED","movement_stalled",0);return "movement_stalled";
    }
    public void transition(String phase,String reason,int nearby){
        HumanLog.log(HumanLog.Level.DEBUG,()->"survival key="+human.definition.key()+"; phase="+phase+"; reason="+reason
            +"; nearby="+nearby+"; health="+human.state.snapshot().vitals().health()+"; endurance="+human.state.snapshot().vitals().endurance());
    }
}
