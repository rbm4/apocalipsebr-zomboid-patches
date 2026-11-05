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
    private long retryAt,urgentRetryAt;
    private Result lastResult;
    private zombie.apocalipse.humans.groups.SquadAssistModule assistance;
    private boolean assisting;
    public void bindAssistance(zombie.apocalipse.humans.groups.SquadAssistPort port){
        if(assistance!=null)throw new IllegalStateException("Squad assistance already bound");
        assistance=new zombie.apocalipse.humans.groups.SquadAssistModule(port);
    }
    public EngageZombiesModule(Settings settings){this(GUARD_ZOMBIES,settings,Template.GUARD_BASIC);}
    public EngageZombiesModule(Policy policy,Settings settings,Template template){
        this.policy=Objects.requireNonNull(policy);this.settings=Objects.requireNonNull(settings);this.template=Objects.requireNonNull(template);
    }
    public boolean eligible(HumanObservation observation){
        if(!available()||suspended)return false;
        if(assistance!=null&&assistance.eligible(observation))return true;
        var next=selector.preview(observation,policy,settings);
        boolean urgent=next.choice()==Choice.ENGAGE&&next.target().distanceSquared()<=HumanCombatTactics.IMMEDIATE_RANGE*HumanCombatTactics.IMMEDIATE_RANGE;
        return (!backingOff||observation.nowNanos()>=retryAt||urgent&&observation.nowNanos()>=urgentRetryAt)&&next.choice()!=Choice.IGNORE;
    }
    public boolean available(){return !stopped;}
    public boolean emergency(HumanObservation observation){
        // Suspension and attack retry timers must not suppress threat monitoring.
        return !stopped&&selector.preview(observation,policy,settings).choice()==Choice.ENGAGE;
    }
    public HumanSenses.Identity target(){return selector.target();}
    public Result lastResult(){return lastResult;}
    public void update(HumanObservation observation,HumanActuator actuator){
        if(!eligible(observation))return;
        if(assistance!=null&&assistance.eligible(observation)){
            if(!assisting){cancel(Reason.PREEMPTED);selector.clear();assisting=true;}
            assistance.update(observation,actuator);return;
        }
        cancelAssistance(actuator);
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
            urgentRetryAt=observation.nowNanos()+300_000_000L;actuator.tactical(TacticalIntent.NONE);
        }
    }
    private void cancel(Reason reason){
        var owned=execution;execution=null;
        if(owned!=null){owned.cancel(reason);lastResult=new Result(Status.CANCELLED,reason);}
    }
    private void cancelAssistance(HumanActuator actuator){if(assisting)assistance.cancel(actuator);assisting=false;}
    public void interrupt(long now,HumanActuator actuator){cancelAssistance(actuator);cancel(Reason.PREEMPTED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
    public void suspend(long now,HumanActuator actuator){suspended=true;cancelAssistance(actuator);cancel(Reason.SUSPENDED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;selector.clear();}
    public void stop(long now,HumanActuator actuator){stopped=true;cancelAssistance(actuator);cancel(Reason.STOPPED);selector.clear();actuator.tactical(TacticalIntent.NONE);}
}
