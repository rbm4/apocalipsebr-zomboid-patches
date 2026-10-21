package zombie.apocalipse.humans;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.IsoZombie;
import zombie.pathfind.PolygonalMap2;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** One combat route owner, using the same physical engine/prediction as roaming. */
final class ServerHumanCombatApproach implements CombatRoutePlanner.Grid {
    private final ServerHuman human;
    private final IsoZombie target;
    private long nextRepath;
    private boolean planned,routeActive;
    private float goalX,goalY;
    private int failures;
    private final HumanCombatTactics tactics;
    private boolean retreating;
    private long retreatDeadline;
    ServerHumanCombatApproach(ServerHuman human,IsoZombie target){this(human,target,()->ThreadLocalRandom.current().nextDouble());}
    ServerHumanCombatApproach(ServerHuman human,IsoZombie target,DoubleSupplier random){this.human=human;this.target=target;tactics=new HumanCombatTactics(random);}
    double distance(){return Math.hypot(target.getX()-human.player.getX(),target.getY()-human.player.getY());}
    boolean clear(float x,float y){
        return !PolygonalMap2.instance.lineClearCollide(x,y,target.getX(),target.getY(),human.player.getZi(),null,false,true)
            &&ServerHumanCombatNetwork.clearBodies(human,target,x,y);
    }
    HumanActuator.MotionStatus update(HumanCombatInventory.Weapon weapon,long now){
        return update(weapon,now,true,false);
    }
    HumanActuator.MotionStatus update(HumanCombatInventory.Weapon weapon,long now,boolean allowClose,boolean recovering){
        boolean lane=clear(human.player.getX(),human.player.getY());
        var order=tactics.update(weapon,distance(),lane,recovering,now);
        if(retreating){
            if(now<retreatDeadline&&human.movement.update(now)==HumanActuator.MotionStatus.RUNNING)return HumanActuator.MotionStatus.RUNNING;
            human.movement.stop();retreating=routeActive=false;
            // Hold after the leg; the same cached order must not restart another escape.
            return HumanActuator.MotionStatus.SUCCEEDED;
        }
        if(order.maneuver()==HumanCombatTactics.Maneuver.WITHDRAW){
            // Consume this order even when cornered. The tactics cooldown remains in force.
            if(lastRetreatOrder!=order){
                lastRetreatOrder=order;human.movement.stop();routeActive=false;
                var route=CombatRoutePlanner.withdraw(this,new Tile(human.player.getXi(),human.player.getYi()),target.getX(),target.getY(),weapon.ranged()?2:1);
                retreating=!route.isEmpty()&&human.movement.start(route,this,MovementModifiers.Pace.WALK,now);
                retreatDeadline=now+900_000_000L;
                if(retreating)return HumanActuator.MotionStatus.RUNNING;
            }
            return HumanActuator.MotionStatus.SUCCEEDED;
        }
        if(!allowClose||distance()<=order.maximum()&&lane||order.maneuver()==HumanCombatTactics.Maneuver.HOLD&&distance()<=weapon.reach()&&lane){
            human.movement.stop();routeActive=false;return HumanActuator.MotionStatus.SUCCEEDED;
        }
        double moved=(target.getX()-goalX)*(target.getX()-goalX)+(target.getY()-goalY)*(target.getY()-goalY);
        if(!planned||now>=nextRepath&&(moved>2.25||!routeActive)){
            if(failures>=3)return HumanActuator.MotionStatus.FAILED;
            var route=CombatRoutePlanner.plan(this,new Tile(human.player.getXi(),human.player.getYi()),target.getX(),target.getY(),order.minimum(),order.maximum());
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
        if(distance()<=order.maximum()&&clear(human.player.getX(),human.player.getY())){human.movement.stop();routeActive=false;return HumanActuator.MotionStatus.SUCCEEDED;}
        return failures>=3?HumanActuator.MotionStatus.FAILED:HumanActuator.MotionStatus.RUNNING;
    }
    private HumanCombatTactics.Order lastRetreatOrder;
    @Override public double placementScore(Tile tile){
        double penalty=0;
        for(var contact:human.perception.snapshot().contacts()){
            if(!contact.visible()||contact.identity().kind()!=HumanSenses.Kind.ZOMBIE)continue;
            double distance=Math.hypot(tile.x()+.5-contact.x(),tile.y()+.5-contact.y());
            if(distance<3)penalty-=2*(3-distance);
        }
        return penalty;
    }
    public boolean walkable(Tile tile){
        var square=human.movement.square(tile.x(),tile.y());
        if(square==null||!square.isFree(true)||!human.movement.walkable(tile)||!human.territory.contains(tile.x()+.5F,tile.y()+.5F,human.player.getZ()))return false;
        boolean current=tile.x()==human.player.getXi()&&tile.y()==human.player.getYi();
        if(!current)for(var contact:human.perception.snapshot().contacts()){
            if(contact.visible()&&contact.identity().kind()==HumanSenses.Kind.ZOMBIE
                &&Math.hypot(tile.x()+.5-contact.x(),tile.y()+.5-contact.y())<.8)return false;
        }
        return true;
    }
    public boolean connected(Tile from,Tile to){return walkable(from)&&walkable(to)&&human.movement.connected(from,to);}
    public boolean clearAttack(Tile from){return clear(from.x()+.5F,from.y()+.5F);}
}
