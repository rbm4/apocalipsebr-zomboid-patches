package zombie.apocalipse.humans.groups;

import zombie.apocalipse.humans.brain.*;
import static zombie.apocalipse.humans.brain.HumanCombat.*;
import static zombie.apocalipse.humans.brain.HumanActuator.MotionStatus.*;

/** Combat assistance composed into zombie engagement, below personal survival and retaliation. */
public final class SquadAssistModule {
    private final SquadAssistPort port;
    private Execution combat;
    private HumanSenses.Identity target;
    private SquadCombatCoordinator.Report assisting;
    private boolean approaching;
    private long retry=Long.MIN_VALUE;
    private long approachDeadline;
    private String phase="IDLE";
    public SquadAssistModule(SquadAssistPort port){this.port=java.util.Objects.requireNonNull(port);}
    private static boolean fresh(SquadAssistPort.Order order,long now){return order!=null&&now>=order.sampledAt()
        &&now-order.sampledAt()<=500_000_000L&&now>=order.report().threat().observedAt()
        &&now-order.report().threat().observedAt()<=SquadCombatCoordinator.MEMORY_NANOS;}
    public boolean eligible(HumanObservation o){return !o.state().dead()&&!o.state().injuries().downed()
        &&!o.state().injuries().immobile()&&fresh(port.observe(),o.nowNanos());}
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o)){cancel(actuator);return;}
        var order=port.observe();var report=order.report();long now=o.nowNanos();
        if(assisting!=null&&(!assisting.ally().equals(report.ally())||!assisting.incarnation().equals(report.incarnation())
            ||!assisting.threat().identity().equals(report.threat().identity()))){cancel(actuator);retry=now;}
        assisting=report;
        var key=report.threat().identity();var frame=o.senses();
        boolean sampled=frame.sampled()&&now>=frame.sampledAtNanos()&&now-frame.sampledAtNanos()<=MAX_SAMPLE_AGE_NANOS
            &&frame.zombies()!=HumanSenses.Coverage.UNAVAILABLE;
        var contact=sampled?frame.contacts().stream().filter(c->c.visible()&&c.identity().kind()==HumanSenses.Kind.ZOMBIE
            &&(c.distanceSquared()<=2.25||c.identity().onlineId()==key.onlineId()&&c.identity().instanceId()==key.instanceId())
            &&now>=c.lastSeenNanos()&&now-c.lastSeenNanos()<=MAX_SAMPLE_AGE_NANOS)
            .min(java.util.Comparator.comparingDouble(HumanSenses.Contact::distanceSquared)).orElse(null):null;
        if(contact!=null){
            if(approaching){port.stopApproach();approaching=false;retry=now;}
            if(combat!=null&&!contact.identity().equals(target)){cancelCombat();retry=now;}
            if(combat==null&&now>=retry){
                target=contact.identity();actuator.stopMoving();
                actuator.tactical(new TacticalIntent(TacticalIntent.Objective.ASSIST,TacticalIntent.Posture.GUARDED,target,false));
                combat=actuator.combat().begin(new Request(target,Template.SQUAD_ASSIST),now);
                transition("ENGAGING",order);
            }
            if(combat!=null){var result=combat.update(now);if(result.terminal()){combat=null;target=null;retry=now+2_000_000_000L;actuator.tactical(TacticalIntent.NONE);}}
        }else {
            cancelCombat();actuator.tactical(TacticalIntent.NONE);
            if(!approaching&&now>=retry){approaching=port.approach(order,now);approachDeadline=now+14_000_000_000L;retry=now+2_000_000_000L;transition(approaching?"APPROACHING":"BLOCKED",order);}
            if(approaching&&(now>=approachDeadline||port.updateApproach(now)!=RUNNING)){
                port.stopApproach();approaching=false;retry=now+2_000_000_000L;transition("SEARCHING",order);
            }
        }
        port.pursuit(order,now,approaching||combat!=null);
    }
    private void transition(String value,SquadAssistPort.Order order){if(!phase.equals(value)){phase=value;port.transition(value,order);}}
    private void cancelCombat(){if(combat!=null)combat.cancel(Reason.PREEMPTED);combat=null;target=null;}
    public void cancel(HumanActuator actuator){cancelCombat();if(approaching)port.stopApproach();approaching=false;assisting=null;phase="IDLE";retry=Long.MIN_VALUE;port.pursuit(null,0,false);actuator.tactical(TacticalIntent.NONE);}
}
