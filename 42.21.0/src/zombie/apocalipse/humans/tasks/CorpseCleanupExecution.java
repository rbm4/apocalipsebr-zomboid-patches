package zombie.apocalipse.humans.tasks;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Persistent activation-local intent: approach alternatives, bounded attempts and timed body deferral. */
public final class CorpseCleanupExecution {
    public static final int MAX_ATTEMPTS=5;
    public static final long IDLE_NANOS=2_000_000_000L, RETRY_NANOS=1_000_000_000L, DEFER_NANOS=300_000_000_000L;
    private Point source;
    private final Point destination;
    private final boolean followActor;
    private final int radius;
    private HumanCorpsePort.Body body;
    private boolean hauling,moving,claimed,returning,needsReturn;
    private Point goal;
    private long deadline,retryAt=Long.MIN_VALUE;
    private int attempts,returnAttempts;
    private String reason="cleanup_searching";
    public CorpseCleanupExecution(Point source,Point destination,int radius){this(source,destination,radius,true);}
    public CorpseCleanupExecution(Point source,Point destination,int radius,boolean followActor){
        this.followActor=followActor;
        this.source=java.util.Objects.requireNonNull(source);this.destination=java.util.Objects.requireNonNull(destination);this.radius=radius;
        if(radius<0||radius>2||source.z()!=destination.z())throw new IllegalArgumentException("Invalid corpse cleanup area");
    }
    public String reason(){return reason;}
    public boolean ready(long now){return now>=retryAt;}
    public boolean hasWork(){return body!=null||returning;}
    /** Discovery only: may run alongside roaming/defense, never claims a body or touches physical movement. */
    public void poll(long now,HumanCorpsePort port,Point here){
        if(now<retryAt||hasWork())return;
        if(!port.supported()||here==null){reason="cleanup_waiting_available";retryAt=now+RETRY_NANOS;return;}
        var center=followActor?here:source;
        if(!port.permitted(center)||!port.permitted(destination)){
            reason="cleanup_waiting_scope";retryAt=now+RETRY_NANOS;return;
        }
        reason="cleanup_searching";
        var found=port.find(center,radius,destination,now);
        if(!found.loaded()){reason="cleanup_waiting_area";retryAt=now+RETRY_NANOS;return;}
        if(found.body()!=null){
            // Keep the patrol return point through a delivery batch, even if more bodies are found near the dump.
            if(!needsReturn)source=center;
            body=found.body();attempts=0;hauling=false;moving=false;needsReturn=true;return;
        }
        if(!found.complete()){retryAt=now+100_000_000L;return;}
        if(found.retryAtNanos()!=Long.MAX_VALUE){
            reason="cleanup_waiting_deferred";retryAt=Math.min(found.retryAtNanos(),now+IDLE_NANOS);return;
        }
        if(needsReturn&&!source.equals(here)){returning=true;reason="cleanup_returning";return;}
        needsReturn=false;reason="cleanup_waiting_corpses";retryAt=now+IDLE_NANOS;
    }
    /** Null while intent is live, including retries and five-minute deferrals. */
    public String update(long now,HumanActuator actuator){
        var port=actuator.corpses();var here=actuator.taskWorld().position();
        poll(now,port,here);
        if(now<retryAt||!hasWork())return null;
        if(!port.supported()||here==null||!port.permitted(source)||!port.permitted(destination)){
            interrupt(actuator);reason="cleanup_waiting_scope";retryAt=now+RETRY_NANOS;return null;
        }
        if(returning)return returnToSource(now,actuator);
        var current=port.resolve(body.token());
        if(current==null||!current.position().equals(body.position())){clear(actuator);return null;}
        if(!claimed){
            if(!port.claim(body.token()))return retryBody("cleanup_claim_busy",now,actuator);
            claimed=true;
        }
        if(!moving){
            if(hauling)goal=destination;
            else {
                var approaches=port.approaches(body.token());
                if(approaches.isEmpty())return retryBody("cleanup_no_approach",now,actuator);
                goal=approaches.get(attempts%approaches.size());
            }
        }
        reason=hauling?"cleanup_hauling":"cleanup_approaching";
        if(here.equals(goal)){
            if(moving)actuator.stopMoving();moving=false;
            if(!hauling){
                // Head/feet are adjacent route endpoints. Pull at most one clear cardinal tile to the actor.
                if(!port.move(body.token(),here))return retryBody("cleanup_grab_blocked",now,actuator);
                body=new HumanCorpsePort.Body(body.token(),here);hauling=true;needsReturn=true;goal=null;return null;
            }
            port.release(body.token());body=null;hauling=false;claimed=false;attempts=0;reason="cleanup_searching";return null;
        }
        if(!moving){
            if(!port.permitted(goal)||!actuator.startMovement(new MovementRequest.To(goal,port.movementSlot()),now))return retryBody("cleanup_route_rejected",now,actuator);
            moving=true;deadline=now+120_000_000_000L;
        }
        if(now>=deadline)return retryBody("cleanup_route_timeout",now,actuator);
        var motion=actuator.updateMovement(now);
        if(hauling){
            var reached=actuator.taskWorld().position();
            if(reached==null||!port.move(body.token(),reached))return retryBody("cleanup_haul_blocked",now,actuator);
            body=new HumanCorpsePort.Body(body.token(),reached);
        }
        if(motion==HumanActuator.MotionStatus.FAILED)return retryBody("cleanup_route_failed",now,actuator);
        if(motion==HumanActuator.MotionStatus.SUCCEEDED){
            moving=false;
            if(!goal.equals(actuator.taskWorld().position()))return retryBody("cleanup_destination_not_reached",now,actuator);
        }
        return null;
    }
    private String retryBody(String why,long now,HumanActuator actuator){
        var port=actuator.corpses();long token=body.token();
        interrupt(actuator);attempts++;
        retryAt=now+RETRY_NANOS;reason="cleanup_retrying";
        if(attempts>=MAX_ATTEMPTS){
            long until=now+DEFER_NANOS;
            if(!port.defer(token,until)){reason="cleanup_waiting_deferral_capacity";retryAt=until;attempts=0;return null;}
            port.retry(token,attempts,why,until);
            body=null;attempts=0;reason="cleanup_searching";
            // Continue with other bodies on the next tick; the deferred body has its own timer.
            retryAt=now;
        }else port.retry(token,attempts,why,retryAt);
        return null;
    }
    private String returnToSource(long now,HumanActuator actuator){
        reason="cleanup_returning";
        if(source.equals(actuator.taskWorld().position()))return idle(now,actuator);
        if(!moving){
            if(!actuator.startMovement(new MovementRequest.To(source,actuator.corpses().movementSlot()),now))return retryReturn("cleanup_return_rejected",now,actuator);
            moving=true;deadline=now+120_000_000_000L;
        }
        if(now>=deadline)return retryReturn("cleanup_return_timeout",now,actuator);
        var motion=actuator.updateMovement(now);
        if(motion==HumanActuator.MotionStatus.FAILED)return retryReturn("cleanup_return_failed",now,actuator);
        if(motion==HumanActuator.MotionStatus.SUCCEEDED){
            moving=false;
            if(!source.equals(actuator.taskWorld().position()))return retryReturn("cleanup_destination_not_reached",now,actuator);
            return idle(now,actuator);
        }
        return null;
    }
    private String idle(long now,HumanActuator actuator){
        if(moving)actuator.stopMoving();moving=false;
        returning=false;needsReturn=false;returnAttempts=0;
        reason="cleanup_waiting_corpses";retryAt=now+IDLE_NANOS;
        return null;
    }
    private String retryReturn(String why,long now,HumanActuator actuator){
        if(moving)actuator.stopMoving();moving=false;
        returnAttempts++;retryAt=now+(returnAttempts>=MAX_ATTEMPTS?DEFER_NANOS:RETRY_NANOS);
        actuator.corpses().retry(0,returnAttempts,why,retryAt);
        if(returnAttempts>=MAX_ATTEMPTS)returnAttempts=0;
        reason="cleanup_retrying_return";return null;
    }
    public void interrupt(HumanActuator actuator){
        if(moving)actuator.stopMoving();moving=false;
        if(body!=null)actuator.corpses().release(body.token());
        claimed=false;hauling=false;goal=null;
    }
    private void clear(HumanActuator actuator){interrupt(actuator);body=null;attempts=0;goal=null;}
}
