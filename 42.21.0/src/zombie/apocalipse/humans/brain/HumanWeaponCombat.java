package zombie.apocalipse.humans.brain;

import java.util.Objects;
import static zombie.apocalipse.humans.brain.HumanCombat.*;

/** Per-engagement phase engine. Only ports access items, movement, packets and native hits. */
public final class HumanWeaponCombat implements Execution {
    public enum Mode { AUTO, MELEE, RANGED }
    public enum Phase { SELECT, EQUIP, APPROACH, WINDUP, RELOAD, RECOVER, FINISHED }
    public interface Operations {
        double distance();
        boolean defeated();
        HumanActuator.MotionStatus approach(HumanCombatInventory.Weapon weapon,long now);
        boolean clearAttackLine(HumanCombatInventory.Weapon weapon);
        void face();
        void stop();
        void aim(boolean enabled);
        boolean commit(HumanCombatInventory.Weapon weapon,long attackId,long now);
    }
    private final HumanCombatInventory inventory;
    private final Operations operations;
    private final Mode mode;
    private Phase phase=Phase.SELECT;
    private HumanCombatInventory.Weapon weapon;
    private long deadline,attackId;
    private Result result=new Result(Status.RUNNING,Reason.NONE);
    public HumanWeaponCombat(HumanCombatInventory inventory,Operations operations,Mode mode){
        this.inventory=Objects.requireNonNull(inventory);this.operations=Objects.requireNonNull(operations);this.mode=Objects.requireNonNull(mode);
    }
    public Phase phase(){return phase;}
    private HumanCombatInventory.Weapon select(){
        var available=inventory.weapons().stream().filter(HumanCombatInventory.Weapon::usable).toList();
        var melee=available.stream().filter(w->!w.ranged()).findFirst().orElse(null);
        var ranged=available.stream().filter(w->w.ranged()&&(w.rounds()>0||w.reserve()>0)).findFirst().orElse(null);
        return switch(mode){case MELEE->melee;case RANGED->ranged;case AUTO->operations.distance()>3&&ranged!=null?ranged:melee!=null?melee:ranged;};
    }
    public Result update(long now){
        if(result.terminal())return result;
        if(operations.defeated())return finish(Status.COMPLETED,Reason.NONE);
        if(phase==Phase.SELECT){
            weapon=select();if(weapon==null)return finish(Status.FAILED,Reason.NO_WEAPON);
            if(!inventory.equip(weapon.itemId()))return finish(Status.FAILED,Reason.NO_WEAPON);
            operations.stop();operations.aim(true);phase=Phase.EQUIP;deadline=now+250_000_000L;return result;
        }
        var current=inventory.weapons().stream().filter(w->w.itemId()==weapon.itemId()&&w.usable()).findFirst().orElse(null);
        if(current==null){operations.stop();operations.aim(false);phase=Phase.SELECT;return result;}
        weapon=current;
        if(phase==Phase.EQUIP){if(now<deadline)return result;phase=Phase.APPROACH;}
        if(phase==Phase.RECOVER){if(now<deadline)return result;phase=Phase.APPROACH;}
        if(phase==Phase.RELOAD){
            if(now<deadline)return result;
            // Commit once; cancellation before this point changes no ammunition.
            phase=Phase.APPROACH;
            if(!inventory.reload(weapon.itemId())){phase=Phase.SELECT;if(mode==Mode.RANGED)return finish(Status.FAILED,Reason.NO_AMMO);}
            return result;
        }
        if(phase==Phase.APPROACH){
            if(weapon.ranged()&&(weapon.rounds()==0||weapon.jammed())){
                if(weapon.reserve()==0&&weapon.rounds()==0){phase=Phase.SELECT;return mode==Mode.RANGED?finish(Status.FAILED,Reason.NO_AMMO):result;}
                operations.stop();operations.aim(true);phase=Phase.RELOAD;deadline=now+1_500_000_000L;return result;
            }
            var status=operations.approach(weapon,now);
            if(status==HumanActuator.MotionStatus.FAILED)return finish(Status.FAILED,Reason.UNREACHABLE);
            if(status==HumanActuator.MotionStatus.RUNNING)return result;
            operations.stop();operations.face();operations.aim(true);phase=Phase.WINDUP;deadline=now+weapon.windupNanos();return result;
        }
        if(phase==Phase.WINDUP){
            operations.face();
            if(operations.distance()>weapon.reach()||!operations.clearAttackLine(weapon)){phase=Phase.APPROACH;return result;}
            if(now<deadline)return result;
            // Enter recovery BEFORE side effects. Polling/cancellation cannot replay a committed strike.
            phase=Phase.RECOVER;deadline=now+weapon.recoveryNanos();long strike=++attackId;
            if(weapon.ranged()&&!inventory.spendRound(weapon.itemId())){phase=Phase.APPROACH;return result;}
            if(!operations.commit(weapon,strike,now))return finish(Status.FAILED,Reason.BACKEND_FAILURE);
            if(operations.defeated())return finish(Status.COMPLETED,Reason.NONE);
        }
        return result;
    }
    private Result finish(Status status,Reason reason){
        result=new Result(status,reason);phase=Phase.FINISHED;
        try{operations.stop();}finally{operations.aim(false);}
        return result;
    }
    public void cancel(Reason reason){if(!result.terminal())finish(Status.CANCELLED,reason);}
}
