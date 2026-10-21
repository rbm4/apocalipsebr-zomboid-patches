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
        var approach=new ServerHumanCombatApproach(human,target);
        var mode=HumanWeaponCombat.Mode.valueOf(System.getProperty("apocbr.npc.combat.testWeapon","AUTO").trim().toUpperCase(Locale.ROOT));
        return new HumanWeaponCombat(human.inventory,new HumanWeaponCombat.Operations(){
            public double distance(){return approach.distance();}
            public boolean defeated(){return target.isDead()||target.getHealth()<=0;}
            public HumanActuator.MotionStatus approach(HumanCombatInventory.Weapon weapon,long at){
                ServerHumanTransport.aim(human,false);
                return approach.update(weapon,at);
            }
            public boolean reposition(HumanCombatInventory.Weapon weapon,long at,boolean recovering){
                boolean moving=approach.update(weapon,at,false,recovering)==HumanActuator.MotionStatus.RUNNING;
                ServerHumanTransport.aim(human,!moving);
                if(!moving)face();
                return moving;
            }
            public boolean clearAttackLine(HumanCombatInventory.Weapon weapon){return approach.clear(actor.getX(),actor.getY())&&ServerHumanCombatNetwork.clearBodies(human,target);}
            public void face(){
                float dx=target.getX()-actor.getX(),dy=target.getY()-actor.getY();
                if(dx!=0||dy!=0)actor.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy,dx)));
            }
            public void stop(){human.movement.stop();}
            public void aim(boolean enabled){ServerHumanTransport.aim(human,enabled);}
            public boolean commit(HumanCombatInventory.Weapon weapon,long strike,long at){
                if(human.perception.resolveZombie(request.target())!=target||distance()>weapon.reach()||!clearAttackLine(weapon))return false;
                return ServerHumanCombatNetwork.commit(human,target,weapon.itemId(),executionId,strike,at);
            }
        },mode);
    }
}
