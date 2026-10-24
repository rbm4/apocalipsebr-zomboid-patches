package zombie.apocalipse.humans.tasks;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.walking.*;
import java.util.Objects;

/** One per actor. Commands replace intent; only the scheduler-selected module actuates. */
public final class AssignedTaskModule implements HumanModule {
    public enum Status { IDLE, ASSIGNED, RUNNING, SUSPENDED, SUCCEEDED, FAILED, CANCELLED }
    public record Snapshot(HumanTaskCommand command,Status status,String reason) { }
    private HumanTaskCommand command;
    private HumanObservation.Identity target;
    private Status status=Status.IDLE;
    private String reason="none";
    private boolean suspended,stopped,moving;
    private long remaining,lastTick,nextReplan;
    private WalkingPatternSpec.Point routeGoal;
    private WalkingPatternSpec.Point cleanupSource;
    private CorpseCleanupExecution cleanup;
    public Snapshot snapshot(){return new Snapshot(command,status,reason);}
    public boolean active(){return status==Status.ASSIGNED||status==Status.RUNNING||status==Status.SUSPENDED;}
    /** Caller owns the exclusive dialogue lease, which has already stopped physical actions. */
    public void assign(HumanTaskCommand value,HumanObservation.Identity issuer) {
        assign(value,issuer,null);
    }
    public void assign(HumanTaskCommand value,HumanObservation.Identity issuer,WalkingPatternSpec.Point source) {
        Objects.requireNonNull(value);
        if(stopped)throw new IllegalStateException("Task module stopped");
        if(value.kind()==HumanTaskCommand.Kind.FOLLOW_PLAYER)Objects.requireNonNull(issuer);
        command=value;target=issuer;remaining=value.kind()==HumanTaskCommand.Kind.HOLD_POSITION?value.durationMs()*1_000_000L:0;
        cleanup=null;cleanupSource=value.source()!=null?value.source():source;
        moving=false;routeGoal=null;lastTick=0;nextReplan=0;
        status=value.kind()==HumanTaskCommand.Kind.RESUME_ROUTINE?Status.CANCELLED:Status.ASSIGNED;
        reason=value.kind()==HumanTaskCommand.Kind.RESUME_ROUTINE?"routine_resumed":"accepted";
    }
    public boolean eligible(HumanObservation observation){return !stopped&&!suspended&&active()&&(cleanup==null||cleanup.ready(observation.nowNanos()));}
    public boolean available(){return !stopped;}
    public void update(HumanObservation observation,HumanActuator actuator) {
        var before=snapshot();tick(observation,actuator);
        if(!before.equals(snapshot()))actuator.taskWorld().changed(snapshot());
    }
    private void tick(HumanObservation observation,HumanActuator actuator) {
        if(!eligible(observation))return;
        long now=observation.nowNanos();status=Status.RUNNING;reason="executing";
        if(command.kind()==HumanTaskCommand.Kind.CLEAR_CORPSES){
            if(cleanup==null){
                if(cleanupSource==null){finish(Status.FAILED,"cleanup_source_unavailable",actuator);return;}
                cleanup=new CorpseCleanupExecution(cleanupSource,command.destination(),command.radiusTiles());
            }
            var outcome=cleanup.update(now,actuator);
            if(outcome==null)reason=cleanup.reason();
            if(outcome!=null)finish(outcome.equals("cleanup_completed")?Status.SUCCEEDED:Status.FAILED,outcome,actuator);
            return;
        }
        if(command.kind()==HumanTaskCommand.Kind.HOLD_POSITION){
            if(command.durationMs()>0){
                if(lastTick!=0)remaining-=Math.max(0,now-lastTick);
                if(remaining<=0)finish(Status.SUCCEEDED,"hold_completed",actuator);
                lastTick=now;
            }
            return;
        }
        var world=actuator.taskWorld();var here=world.position();
        var goal=command.kind()==HumanTaskCommand.Kind.MOVE_TO?command.destination():world.locate(target);
        if(here==null||goal==null){finish(Status.FAILED,"target_unavailable",actuator);return;}
        int distance=command.kind()==HumanTaskCommand.Kind.FOLLOW_PLAYER?command.distanceTiles():0;
        long dx=(long)here.x()-goal.x(),dy=(long)here.y()-goal.y();
        if(here.z()==goal.z()&&dx*dx+dy*dy<=(long)distance*distance){
            if(moving)actuator.stopMoving();moving=false;routeGoal=null;
            if(command.kind()==HumanTaskCommand.Kind.MOVE_TO)finish(Status.SUCCEEDED,"destination_reached",actuator);
            return;
        }
        if(command.kind()==HumanTaskCommand.Kind.FOLLOW_PLAYER)goal=world.approach(goal,here,distance);
        if(goal==null||!world.permitted(goal)){finish(Status.FAILED,"outside_movement_scope",actuator);return;}
        if(moving&&command.kind()==HumanTaskCommand.Kind.FOLLOW_PLAYER&&now>=nextReplan&&!goal.equals(routeGoal)){
            actuator.stopMoving();moving=false;
        }
        if(!moving){
            if(!actuator.startMovement(new MovementRequest.To(goal),now)){finish(Status.FAILED,"route_rejected",actuator);return;}
            moving=true;routeGoal=goal;nextReplan=now+1_000_000_000L;
        }
        var result=actuator.updateMovement(now);
        if(result==HumanActuator.MotionStatus.FAILED)finish(Status.FAILED,"route_failed",actuator);
        else if(result==HumanActuator.MotionStatus.SUCCEEDED){
            moving=false;routeGoal=null;
            if(command.kind()==HumanTaskCommand.Kind.MOVE_TO)finish(Status.SUCCEEDED,"destination_reached",actuator);
        }
    }
    private void finish(Status next,String why,HumanActuator actuator){if(moving)actuator.stopMoving();moving=false;status=next;reason=why;}
    public void interrupt(long now,HumanActuator actuator){
        if(cleanup!=null)cleanup.interrupt(actuator);
        if(moving)actuator.stopMoving();moving=false;lastTick=0;routeGoal=null;
        if(active()&&status!=Status.SUSPENDED){status=Status.SUSPENDED;reason="preempted";actuator.taskWorld().changed(snapshot());}
    }
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;lastTick=0;}
    public void stop(long now,HumanActuator actuator){interrupt(now,actuator);stopped=true;status=Status.CANCELLED;reason="actor_stopped";}
}
