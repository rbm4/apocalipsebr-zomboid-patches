package zombie.apocalipse.humans.survival;

import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanActuator.MotionStatus.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;

/** Escape stays the objective while guarded movement, opening creation and recovery change tactics. */
public final class SurvivalModule implements HumanModule {
    public enum Phase { SAFE, ESCAPING, RECOVERING, CORNERED, CREATE_OPENING }
    private final SurvivalSettings settings;
    private final HumanEngagement.Selector selector=new HumanEngagement.Selector();
    private Phase phase=Phase.SAFE;
    private boolean stopped,suspended;
    private int failedAttempts;
    private long started,nextAttempt,nextDefense,routeDeadline,openingUntil,clearSince=Long.MIN_VALUE,controlledSince=Long.MIN_VALUE;
    private Execution defense;
    private boolean externalDefense;
    private HumanSenses.Identity defended;
    public SurvivalModule(SurvivalSettings settings){this.settings=java.util.Objects.requireNonNull(settings);}
    public Phase phase(){return phase;}
    public boolean available(){return !stopped;}
    public boolean eligible(HumanObservation o){
        return !stopped&&!suspended&&!o.state().dead()&&(phase!=Phase.SAFE||ThreatAssessment.assess(o,settings).pressured());
    }
    private void transition(Phase value,String reason,ThreatAssessment facts,HumanActuator actuator){
        if(phase==value)return;phase=value;actuator.survival().transition(value.name(),reason,facts.nearby());
    }
    private void cancelDefense(){if(defense!=null){defense.cancel(Reason.PREEMPTED);defense=null;defended=null;}}
    private HumanSenses.Contact target(HumanObservation o,HumanActuator actuator){
        var selection=selector.preview(o,HumanEngagement.GUARD_ZOMBIES,HumanEngagement.Settings.DEFAULT);
        if(phase==Phase.CREATE_OPENING){
            var candidates=o.senses().contacts().stream().filter(c->c.distanceSquared()<=2.25
                &&HumanEngagement.GUARD_ZOMBIES.evaluate(o,c,HumanEngagement.Settings.DEFAULT)==HumanEngagement.Choice.ENGAGE).toList();
            var blocker=actuator.survival().openingTarget(candidates);
            if(blocker!=null&&candidates.contains(blocker))selection=new HumanEngagement.Selection(blocker,HumanEngagement.Choice.ENGAGE);
        }
        selector.retain(selection);return selection.target();
    }
    private void publish(HumanActuator actuator,HumanSenses.Contact target){
        var objective=phase==Phase.CREATE_OPENING?TacticalIntent.Objective.CREATE_OPENING
            :phase==Phase.RECOVERING?TacticalIntent.Objective.RECOVER:TacticalIntent.Objective.ESCAPE;
        boolean close=target!=null&&target.visible()&&target.distanceSquared()<=2.25;
        actuator.tactical(new TacticalIntent(objective,close?TacticalIntent.Posture.GUARDED:
            phase==Phase.ESCAPING?TacticalIntent.Posture.FLEEING:TacticalIntent.Posture.RELAXED,
            close?target.identity():null,failedAttempts>=2));
    }
    private boolean defend(HumanObservation o,HumanActuator actuator,HumanSenses.Contact target,boolean external){
        if(target==null||!target.visible()||target.distanceSquared()>2.25){cancelDefense();return false;}
        if(defense!=null&&(!target.identity().equals(defended)||externalDefense!=external)){cancelDefense();nextDefense=o.nowNanos();}
        if(defense==null&&o.nowNanos()>=nextDefense){
            defended=target.identity();externalDefense=external;
            defense=actuator.combat().begin(new Request(defended,Template.GUARD_BASIC,external),o.nowNanos());
        }
        if(defense==null)return false;
        var result=defense.update(o.nowNanos());
        if(!result.terminal())return false;
        defense=null;defended=null;nextDefense=o.nowNanos()+Math.min(settings.retryMs(),300)*1_000_000L;
        return result.status()==Status.COMPLETED;
    }
    private void failed(long now,String reason,ThreatAssessment facts,HumanActuator actuator){
        failedAttempts++;
        if(failedAttempts>=2){openingUntil=now+4_000_000_000L;nextAttempt=openingUntil;nextDefense=now;
            transition(Phase.CREATE_OPENING,"repeated_"+reason,facts,actuator);}
        else {nextAttempt=now+settings.retryMs()*1_000_000L;transition(Phase.CORNERED,reason,facts,actuator);}
    }
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o))return;
        long now=o.nowNanos();var facts=ThreatAssessment.assess(o,settings);
        if(phase==Phase.SAFE){started=now;nextAttempt=now;failedAttempts=0;clearSince=controlledSince=Long.MIN_VALUE;
            transition(Phase.RECOVERING,facts.pressure(),facts,actuator);}
        boolean clear=facts.reliable()&&facts.nearest()>settings.safeDistance()*settings.safeDistance()&&!facts.pressured();
        if(clear){if(clearSince==Long.MIN_VALUE)clearSince=now;}else clearSince=Long.MIN_VALUE;
        boolean imminent=facts.nearest()<=HumanCombatTactics.IMMEDIATE_RANGE*HumanCombatTactics.IMMEDIATE_RANGE;
        boolean controlled=facts.reliable()&&!facts.pressured()&&!imminent;
        if(controlled){if(controlledSince==Long.MIN_VALUE)controlledSince=now;}else controlledSince=Long.MIN_VALUE;
        var vitals=o.state().vitals();
        if((clearSince!=Long.MIN_VALUE&&now-clearSince>=settings.safeHoldMs()*1_000_000L
            ||controlledSince!=Long.MIN_VALUE&&now-controlledSince>=Math.max(settings.safeHoldMs(),settings.minimumRetreatMs())*1_000_000L)
            &&now-started>=settings.minimumRetreatMs()*1_000_000L&&vitals.health()>=settings.reengageHealth()&&vitals.endurance()>=settings.reengageEndurance()){
            cancelDefense();actuator.stopMoving();actuator.tactical(TacticalIntent.NONE);failedAttempts=0;
            transition(Phase.SAFE,"recovered",facts,actuator);return;
        }
        // Distant contacts alone must not hold an opening/retry loop after local pressure ends.
        if(controlled&&phase!=Phase.RECOVERING){
            cancelDefense();actuator.stopMoving();failedAttempts=0;nextAttempt=now;openingUntil=now;
            transition(Phase.RECOVERING,"pressure_relieved",facts,actuator);
        }
        var target=target(o,actuator);publish(actuator,target);
        if(phase==Phase.ESCAPING){
            var result=actuator.updateMovement(now);
            String problem=result==FAILED?"route_failed":result==SUCCEEDED?null:now>=routeDeadline?"route_timeout":actuator.survival().routeProblem(now);
            if(result==RUNNING&&problem==null){defend(o,actuator,target,true);publish(actuator,target);return;}
            if(result!=SUCCEEDED)actuator.survival().failed("route_timeout".equals(problem),now);
            cancelDefense();actuator.stopMoving();
            if(result==SUCCEEDED){failedAttempts=0;nextAttempt=now+settings.retryMs()*1_000_000L;transition(Phase.RECOVERING,"arrived",facts,actuator);}
            else failed(now,problem,facts,actuator);
        }
        if(phase==Phase.CREATE_OPENING&&now>=openingUntil)transition(Phase.CORNERED,"opening_window_ended",facts,actuator);
        boolean nearby=imminent||facts.pressured()&&!facts.threats().isEmpty();
        if(nearby&&now>=nextAttempt&&phase!=Phase.CREATE_OPENING){
            cancelDefense();actuator.stopMoving();publish(actuator,target);
            if(o.movementAvailable()&&!o.state().injuries().immobile()&&!o.state().injuries().downed()
                &&actuator.survival().escape(facts.threats(),settings,now,failedAttempts>=2)){
                routeDeadline=now+settings.routeTimeoutMs()*1_000_000L;
                transition(Phase.ESCAPING,"route_started",facts,actuator);publish(actuator,target);return;
            }
            failed(now,"no_safe_route",facts,actuator);
        }
        target=target(o,actuator);publish(actuator,target);
        if(nearby){if(defend(o,actuator,target,false)&&phase==Phase.CREATE_OPENING){openingUntil=now;nextAttempt=now;}}
        else {cancelDefense();transition(Phase.RECOVERING,"threat_separated",facts,actuator);}
        publish(actuator,target);
    }
    public void interrupt(long now,HumanActuator actuator){
        cancelDefense();selector.clear();actuator.stopMoving();actuator.tactical(TacticalIntent.NONE);
        if(phase!=Phase.SAFE)phase=Phase.RECOVERING;clearSince=controlledSince=Long.MIN_VALUE;nextAttempt=now;
    }
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;clearSince=controlledSince=Long.MIN_VALUE;nextAttempt=now;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
