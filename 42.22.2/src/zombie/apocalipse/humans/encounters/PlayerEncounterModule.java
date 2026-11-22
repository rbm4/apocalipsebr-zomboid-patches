package zombie.apocalipse.humans.encounters;

import java.util.*;
import zombie.apocalipse.humans.brain.*;

/** Per-actor challenge execution. Only this selected module speaks/faces; emergency queries are pure. */
public final class PlayerEncounterModule implements HumanModule {
    public enum Phase { IDLE, WARNING, VERIFYING, STOPPED }
    private final EncounterPolicy policy;
    private EncounterPort port;
    private EncounterPort.Player target;
    private Phase phase=Phase.IDLE;
    private boolean suspended;
    private long deadline,complianceSince=-1,retryAt;
    private HumanActionExecution speech;
    public PlayerEncounterModule(EncounterPolicy policy){this.policy=Objects.requireNonNull(policy);if(!policy.enabled())throw new IllegalArgumentException("Encounter policy required");}
    public void bind(EncounterPort port){if(this.port!=null)throw new IllegalStateException("Already bound");this.port=Objects.requireNonNull(port);}
    public Phase phase(){return phase;}
    private EncounterPort.Player current(){return target==null?null:port.players().stream().filter(p->p.identity().equals(target.identity())&&p.account().equals(target.account())).findFirst().orElse(null);}
    private EncounterPort.Player candidate(){
        long now=port.wallTimeMs();
        return port.players().stream().filter(p->p.distanceSquared()<=policy.challengeTiles()*policy.challengeTiles())
            .filter(p->{var r=port.relationship(p);return now>=r.deniedUntilMs()&&now>=r.nextEncounterMs()&&(p.armed()||!r.permits(now));})
            .min(Comparator.comparingDouble(EncounterPort.Player::distanceSquared).thenComparingInt(p->p.identity().onlineId())).orElse(null);
    }
    public boolean available(){return phase!=Phase.STOPPED;}
    public boolean eligible(HumanObservation o){return available()&&!suspended&&port!=null&&o.nowNanos()>=retryAt&&(target!=null||candidate()!=null);}
    /** Armed challenges cannot be bypassed by acquiring a dialogue or trade lease. */
    public boolean emergency(HumanObservation o){return available()&&port!=null&&port.players().stream().anyMatch(p->p.distanceSquared()<=policy.challengeTiles()*policy.challengeTiles()&&p.armed());}
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o))return;
        long now=o.nowNanos();
        if(target==null){
            var next=candidate();if(next==null)return;
            boolean returning=port.relationship(next).meetings()>0;
            if(!port.claim(next,this,now)){retryAt=now+1_000_000_000L;return;}
            target=next;
            if(!port.record(target,HumanRelationships.Outcome.GREETED,policy)){reset(now,actuator);return;}
            actuator.stopMoving();
            if(!actuator.face(target.identity())){reset(now,actuator);return;}
            if(!target.armed()){
                if(say(returning?"returning":"greeting",now,actuator))complete(HumanRelationships.Outcome.COMPLIED,null,now,actuator);
                else reset(now,actuator);
                return;
            }
            if(!say("warning",now,actuator)){reset(now,actuator);return;}
            phase=Phase.WARNING;deadline=now+policy.warningMs()*1_000_000L;return;
        }
        var live=current();
        if(live==null||live.distanceSquared()>policy.challengeTiles()*policy.challengeTiles()){
            complete(HumanRelationships.Outcome.LEFT,null,now,actuator);return;
        }
        if(!port.claim(live,this,now)||!actuator.face(live.identity())){reset(now,actuator);return;}
        if(!live.armed()){
            if(complianceSince<0)complianceSince=now;
            phase=Phase.VERIFYING;
            if(now-complianceSince>=policy.complianceMs()*1_000_000L){complete(HumanRelationships.Outcome.COMPLIED,"accepted",now,actuator);return;}
        }else{complianceSince=-1;phase=Phase.WARNING;}
        if(now>=deadline)complete(HumanRelationships.Outcome.REFUSED,"refused",now,actuator);
    }
    private boolean say(String event,long now,HumanActuator actuator){
        String text=port.text(event);if(text==null)return false;
        speech=new HumanActionExecution(new HumanAction.Say(text));speech.update(now,actuator);
        return speech.status()==HumanActionExecution.Status.SUCCEEDED;
    }
    private void complete(HumanRelationships.Outcome result,String text,long now,HumanActuator actuator){
        if(port.record(target,result,policy)&&text!=null)say(text,now,actuator);
        reset(now,actuator);
    }
    private void reset(long now,HumanActuator actuator){
        if(speech!=null&&!speech.terminal())speech.cancel(actuator);
        speech=null;if(target!=null)port.release(target,this);target=null;complianceSince=-1;
        if(available())phase=Phase.IDLE;retryAt=now+1_000_000_000L;
    }
    public void interrupt(long now,HumanActuator actuator){reset(now,actuator);}
    public void suspend(long now,HumanActuator actuator){reset(now,actuator);suspended=true;}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;}
    public void stop(long now,HumanActuator actuator){reset(now,actuator);phase=Phase.STOPPED;}
}
