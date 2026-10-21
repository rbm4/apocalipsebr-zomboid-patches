package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.IsoZombie;
import zombie.pathfind.PolygonalMap2;

/** One combat route owner, using the same physical engine/prediction as roaming. */
final class ServerHumanCombatApproach implements CombatRoutePlanner.Grid {
    private final ServerHuman human;
    private final IsoZombie target;
    private long nextRepath;
    private boolean planned,routeActive;
    private float goalX,goalY;
    private int failures;
    ServerHumanCombatApproach(ServerHuman human,IsoZombie target){this.human=human;this.target=target;}
    double distance(){return Math.hypot(target.getX()-human.player.getX(),target.getY()-human.player.getY());}
    boolean clear(float x,float y){
        return !PolygonalMap2.instance.lineClearCollide(x,y,target.getX(),target.getY(),human.player.getZi(),null,false,true)
            &&ServerHumanCombatNetwork.clearBodies(human,target,x,y);
    }
    HumanActuator.MotionStatus update(HumanCombatInventory.Weapon weapon,long now){
        if(distance()<=weapon.reach()&&clear(human.player.getX(),human.player.getY())){human.movement.stop();return HumanActuator.MotionStatus.SUCCEEDED;}
        double moved=(target.getX()-goalX)*(target.getX()-goalX)+(target.getY()-goalY)*(target.getY()-goalY);
        if(!planned||now>=nextRepath&&(moved>2.25||!routeActive)){
            if(failures>=3)return HumanActuator.MotionStatus.FAILED;
            var route=CombatRoutePlanner.plan(this,new Tile(human.player.getXi(),human.player.getYi()),target.getX(),target.getY(),weapon.reach()*.95F);
            nextRepath=now+700_000_000L;goalX=target.getX();goalY=target.getY();planned=true;
            var pace=distance()>3.5?MovementModifiers.Pace.RUN:MovementModifiers.Pace.WALK;
            routeActive=!route.isEmpty()&&human.movement.start(route,this,pace,now);
            if(!routeActive){failures++;return HumanActuator.MotionStatus.RUNNING;}
        }
        if(!routeActive)return HumanActuator.MotionStatus.RUNNING;
        human.movement.requestPace(distance()>3.5?MovementModifiers.Pace.RUN:MovementModifiers.Pace.WALK);
        var motion=human.movement.update(now);
        if(motion!=HumanActuator.MotionStatus.RUNNING)routeActive=false;
        if(motion==HumanActuator.MotionStatus.FAILED){failures++;human.movement.stop();}
        if(distance()<=weapon.reach()&&clear(human.player.getX(),human.player.getY())){human.movement.stop();return HumanActuator.MotionStatus.SUCCEEDED;}
        return failures>=3?HumanActuator.MotionStatus.FAILED:HumanActuator.MotionStatus.RUNNING;
    }
    public boolean walkable(Tile tile){
        var square=human.movement.square(tile.x(),tile.y());
        return square!=null&&square.isFree(true)&&human.movement.walkable(tile)&&human.territory.contains(tile.x()+.5F,tile.y()+.5F,human.player.getZ());
    }
    public boolean connected(Tile from,Tile to){return walkable(from)&&walkable(to)&&human.movement.connected(from,to);}
    public boolean clearAttack(Tile from){return clear(from.x()+.5F,from.y()+.5F);}
}
