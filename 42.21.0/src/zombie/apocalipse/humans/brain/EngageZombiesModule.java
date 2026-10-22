package zombie.apocalipse.humans.brain;

import java.util.Objects;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
import static zombie.apocalipse.humans.brain.HumanEngagement.*;

/** Selected-module owner of one engagement and one combat execution. */
public final class EngageZombiesModule implements HumanModule {
    private final Policy policy;
    private final Settings settings;
    private final Template template;
    private final Selector selector=new Selector();
    private Execution execution;
    private boolean suspended,stopped,backingOff;
    private long retryAt;
    private Result lastResult;
    public EngageZombiesModule(Settings settings){this(GUARD_ZOMBIES,settings,Template.GUARD_BASIC);}
    public EngageZombiesModule(Policy policy,Settings settings,Template template){
        this.policy=Objects.requireNonNull(policy);this.settings=Objects.requireNonNull(settings);this.template=Objects.requireNonNull(template);
    }
    public boolean eligible(HumanObservation observation){
        return available()&&!suspended&&(!backingOff||observation.nowNanos()>=retryAt)
            &&selector.preview(observation,policy,settings).choice()!=Choice.IGNORE;
    }
    public boolean available(){return !stopped;}
    public HumanSenses.Identity target(){return selector.target();}
    public Result lastResult(){return lastResult;}
    public void update(HumanObservation observation,HumanActuator actuator){
        if(!eligible(observation))return;
        var next=selector.preview(observation,policy,settings);
        boolean changed=!Objects.equals(selector.target(),next.target().identity());
        if(changed)cancel(Reason.TARGET_CHANGED);
        selector.retain(next);
        if(next.choice()==Choice.WATCH){cancel(Reason.TARGET_LOST);actuator.stopMoving();actuator.tactical(TacticalIntent.NONE);return;}
        if(execution==null){
            actuator.tactical(new TacticalIntent(TacticalIntent.Objective.ENGAGE,TacticalIntent.Posture.GUARDED,next.target().identity(),false));
            actuator.stopMoving();
            execution=Objects.requireNonNull(actuator.combat().begin(new Request(next.target().identity(),template),observation.nowNanos()));
        }
        lastResult=Objects.requireNonNull(execution.update(observation.nowNanos()));
        if(lastResult.terminal()){
            execution=null;selector.clear();backingOff=true;retryAt=observation.nowNanos()+settings.retryNanos();
        }
    }
    private void cancel(Reason reason){
        var owned=execution;execution=null;
        if(owned!=null){owned.cancel(reason);lastResult=new Result(Status.CANCELLED,reason);}
    }
    public void interrupt(long now,HumanActuator actuator){cancel(Reason.PREEMPTED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
    public void suspend(long now,HumanActuator actuator){suspended=true;cancel(Reason.SUSPENDED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;selector.clear();}
    public void stop(long now,HumanActuator actuator){stopped=true;cancel(Reason.STOPPED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
}
