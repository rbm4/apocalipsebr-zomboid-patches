package zombie.apocalipse.humans;

import java.util.Locale;
import zombie.apocalipse.humans.brain.*;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;

/** Composition of the phase engine with inventory, movement and native packet/hit adapters. */
final class ServerHumanWeaponBackend implements ServerHumanCombat.Backend {
    private final ServerHuman human;
    ServerHumanWeaponBackend(ServerHuman human){this.human=human;}
    public HumanCombat.Execution begin(IsoPlayer actor,IsoZombie target,long executionId,HumanCombat.Request request,long now){
        if(!request.externalMovement()&&human.actuator.intent().objective()==TacticalIntent.Objective.NONE)
            human.actuator.tactical(new TacticalIntent(TacticalIntent.Objective.ENGAGE,TacticalIntent.Posture.GUARDED,request.target(),false));
        var approach=new ServerHumanCombatApproach(human,target);
        var mode=HumanWeaponCombat.Mode.valueOf(System.getProperty("apocbr.npc.combat.testWeapon","AUTO").trim().toUpperCase(Locale.ROOT));
        return new HumanWeaponCombat(human.inventory,new HumanWeaponCombat.Operations(){
            public boolean canAttackMoving(){return human.actuator.intent().guarded()&&!human.movement.traversing();}
            public double distance(){return approach.distance();}
            public boolean defeated(){return target.isDead()||target.getHealth()<=0;}
            public HumanActuator.MotionStatus approach(HumanCombatInventory.Weapon weapon,long at){
                if(request.externalMovement()&&human.movement.traversing())return HumanActuator.MotionStatus.RUNNING;
                if(request.externalMovement())return distance()<=weapon.reach()&&clearAttackLine(weapon)?HumanActuator.MotionStatus.SUCCEEDED:HumanActuator.MotionStatus.FAILED;
                posture(distance()<=3.5);
                var motion=approach.update(weapon,at);
                if(human.actuator.intent().guarded()&&!human.movement.traversing())face();
                return motion;
            }
            public boolean reposition(HumanCombatInventory.Weapon weapon,long at,boolean recovering){
                if(request.externalMovement())return false;
                boolean moving=approach.update(weapon,at,false,recovering)==HumanActuator.MotionStatus.RUNNING;
                if(human.actuator.intent().guarded()&&!human.movement.traversing())face();
                return moving;
            }
            public boolean clearAttackLine(HumanCombatInventory.Weapon weapon){return !human.movement.traversing()&&approach.clear(actor.getX(),actor.getY())&&ServerHumanCombatNetwork.clearBodies(human,target);}
            private void posture(boolean guarded){
                var intent=human.actuator.intent();
                human.actuator.tactical(new TacticalIntent(intent.objective(),guarded?TacticalIntent.Posture.GUARDED:TacticalIntent.Posture.RELAXED,request.target(),intent.elevatedExposure()));
            }
            public void face(){
                if(human.movement.traversing())return;
                human.actuator.tactical(human.actuator.intent());
                float dx=target.getX()-actor.getX(),dy=target.getY()-actor.getY();
                if(dx!=0||dy!=0)actor.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
            }
            public void stop(){if(!request.externalMovement())human.movement.stop();}
            public void aim(boolean enabled){if(!request.externalMovement()){if(enabled)posture(true);else ServerHumanTransport.aim(human,false);}}
            public boolean commit(HumanCombatInventory.Weapon weapon,long strike,long at){
                if(human.perception.resolveZombie(request.target())!=target||distance()>weapon.reach()||!clearAttackLine(weapon))return false;
                return ServerHumanCombatNetwork.commit(human,target,weapon.itemId(),executionId,strike,at);
            }
        },mode);
    }
}
