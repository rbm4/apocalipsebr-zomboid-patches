package zombie.apocalipse.humans.survival;

import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanActuator.MotionStatus.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;

/** Owns fight/flight hysteresis and recovery; movement and individual attacks remain capability executions. */
public final class SurvivalModule implements HumanModule {
    public enum Phase { SAFE, ESCAPING, RECOVERING, CORNERED }
    private final SurvivalSettings settings;
    private Phase phase=Phase.SAFE;
    private boolean stopped,suspended;
    private long started,nextAttempt,nextDefense,routeDeadline,clearSince=Long.MIN_VALUE;
    private Execution defense;
    private zombie.apocalipse.humans.brain.HumanSenses.Identity defended;
    public SurvivalModule(SurvivalSettings settings){this.settings=java.util.Objects.requireNonNull(settings);}
    public Phase phase(){return phase;}
    public boolean available(){return !stopped;}
    public boolean eligible(HumanObservation o){
        if(stopped||suspended||o.state().dead())return false;
        return phase!=Phase.SAFE||ThreatAssessment.assess(o,settings).pressured();
    }
    private void transition(Phase value,String reason,ThreatAssessment facts,HumanActuator actuator){
        if(phase==value)return;phase=value;actuator.survival().transition(value.name(),reason,facts.nearby());
    }
    private void cancelDefense(){if(defense!=null){defense.cancel(Reason.PREEMPTED);defense=null;defended=null;}}
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o))return;
        long now=o.nowNanos();var facts=ThreatAssessment.assess(o,settings);
        if(phase==Phase.SAFE){started=now;nextAttempt=now;clearSince=Long.MIN_VALUE;
            transition(Phase.RECOVERING,facts.pressure(),facts,actuator);}
        boolean clear=facts.reliable()&&facts.nearest()>settings.safeDistance()*settings.safeDistance()&&!facts.pressured();
        if(clear){if(clearSince==Long.MIN_VALUE)clearSince=now;}else clearSince=Long.MIN_VALUE;
        var vitals=o.state().vitals();
        if(clearSince!=Long.MIN_VALUE&&now-clearSince>=settings.safeHoldMs()*1_000_000L
            &&now-started>=settings.minimumRetreatMs()*1_000_000L
            &&vitals.health()>=settings.reengageHealth()&&vitals.endurance()>=settings.reengageEndurance()){
            cancelDefense();actuator.stopMoving();transition(Phase.SAFE,"recovered",facts,actuator);return;
        }
        if(phase==Phase.ESCAPING){
            var result=actuator.updateMovement(now);
            String problem=result==FAILED?"route_failed":result==SUCCEEDED?null:now>=routeDeadline?"route_timeout":actuator.survival().routeProblem(now);
            if(result==RUNNING&&problem==null)return;
            if(result!=SUCCEEDED)actuator.survival().failed("route_timeout".equals(problem),now);
            actuator.stopMoving();nextAttempt=now+settings.retryMs()*1_000_000L;
            transition(result==SUCCEEDED?Phase.RECOVERING:Phase.CORNERED,
                result==SUCCEEDED?"arrived":problem,facts,actuator);
        }
        boolean nearby=facts.nearest()<=settings.safeDistance()*settings.safeDistance();
        if(nearby&&now>=nextAttempt){
            cancelDefense();actuator.stopMoving();nextAttempt=now+settings.retryMs()*1_000_000L;
            if(o.movementAvailable()&&!o.state().injuries().immobile()&&!o.state().injuries().downed()
                &&actuator.survival().escape(facts.threats(),settings,now)){
                routeDeadline=now+settings.routeTimeoutMs()*1_000_000L;
                transition(Phase.ESCAPING,"route_started",facts,actuator);return;
            }
            transition(Phase.CORNERED,"no_safe_route",facts,actuator);
        }
        // A cornered actor can defend against immediate contact, without resuming pursuit.
        var selection=new HumanEngagement.Selector().preview(o,HumanEngagement.GUARD_ZOMBIES,HumanEngagement.Settings.DEFAULT);
        var target=selection.target();
        if(nearby&&target!=null&&target.visible()&&target.distanceSquared()<=2.25){
            if(defense==null&&now>=nextDefense){defended=target.identity();defense=actuator.combat().begin(new Request(defended,Template.GUARD_BASIC),now);}
            if(defense!=null&&!target.identity().equals(defended)){cancelDefense();nextDefense=now+settings.retryMs()*1_000_000L;}
            if(defense!=null&&defense.update(now).terminal()){defense=null;defended=null;nextDefense=now+settings.retryMs()*1_000_000L;}
        }else {cancelDefense();if(!nearby)transition(Phase.RECOVERING,"threat_separated",facts,actuator);}
    }
    public void interrupt(long now,HumanActuator actuator){
        cancelDefense();actuator.stopMoving();if(phase!=Phase.SAFE)phase=Phase.RECOVERING;
        clearSince=Long.MIN_VALUE;nextAttempt=now;
    }
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;clearSince=Long.MIN_VALUE;nextAttempt=now;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
