package zombie.apocalipse.humans.tasks;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.walking.*;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** One body at a time, bounded discovery, scoped routes and release before defensive preemption. */
public final class CorpseCleanupExecution {
    private final Point source,destination;
    private final int radius;
    private HumanCorpsePort.Body body;
    private boolean hauling,moving,claimed,returning;
    private Point goal;
    private long deadline;
    private String result;
    public CorpseCleanupExecution(Point source,Point destination,int radius){
        this.source=java.util.Objects.requireNonNull(source);this.destination=java.util.Objects.requireNonNull(destination);this.radius=radius;
        if(radius<0||radius>2||source.z()!=destination.z())throw new IllegalArgumentException("Invalid corpse cleanup area");
    }
    /** Null while running; otherwise a terminal success/failure reason. */
    public String update(long now,HumanActuator actuator){
        if(result!=null)return result;
        var port=actuator.corpses();var here=actuator.taskWorld().position();
        if(here==null||!port.supported()||!port.permitted(source)||!port.permitted(destination))return finish("cleanup_outside_scope",actuator);
        if(returning)return returnToSource(now,actuator);
        if(body==null){
            var found=port.find(source,radius,destination);
            if(!found.loaded())return finish("cleanup_area_unloaded",actuator);
            if(found.body()==null){
                if(!found.complete())return null;
                returning=true;return returnToSource(now,actuator);
            }
            body=found.body();
            if(!port.claim(body.token())){body=null;return null;}
            claimed=true;
            hauling=false;moving=false;
        }
        var current=port.resolve(body.token());
        // Removed/reanimated/taken by another actor: abandon this body without recreating it.
        if(current==null||!current.position().equals(body.position())){clear(actuator);return null;}
        if(!claimed){if(!port.claim(body.token()))return null;claimed=true;}
        Point target=hauling?destination:body.position();
        if(here.equals(target)){
            if(moving)actuator.stopMoving();moving=false;
            if(!hauling){hauling=true;goal=null;return null;}
            if(!port.move(body.token(),destination))return finish("cleanup_body_lost",actuator);
            port.release(body.token());body=null;hauling=false;claimed=false;return null;
        }
        if(!moving){
            if(!port.permitted(target)||!actuator.startMovement(new MovementRequest.To(target,port.movementSlot()),now))return finish("cleanup_route_rejected",actuator);
            goal=target;moving=true;deadline=now+120_000_000_000L;
        }
        if(now>=deadline)return finish("cleanup_route_timeout",actuator);
        var motion=actuator.updateMovement(now);
        if(hauling){
            // Follow only tiles reached by the collision-checked route; the corpse remains world-owned.
            var reached=actuator.taskWorld().position();
            if(reached==null||!port.move(body.token(),reached))return finish("cleanup_body_lost",actuator);
            body=new HumanCorpsePort.Body(body.token(),reached);
        }
        if(motion==HumanActuator.MotionStatus.FAILED)return finish("cleanup_route_failed",actuator);
        if(motion==HumanActuator.MotionStatus.SUCCEEDED){
            moving=false;
            if(!goal.equals(actuator.taskWorld().position()))return finish("cleanup_destination_not_reached",actuator);
        }
        return null;
    }
    private String returnToSource(long now,HumanActuator actuator){
        if(source.equals(actuator.taskWorld().position()))return finish("cleanup_completed",actuator);
        if(!moving){
            if(!actuator.startMovement(new MovementRequest.To(source,actuator.corpses().movementSlot()),now))return finish("cleanup_return_rejected",actuator);
            moving=true;deadline=now+120_000_000_000L;
        }
        if(now>=deadline)return finish("cleanup_return_timeout",actuator);
        var motion=actuator.updateMovement(now);
        if(motion==HumanActuator.MotionStatus.FAILED)return finish("cleanup_return_failed",actuator);
        if(motion==HumanActuator.MotionStatus.SUCCEEDED){
            moving=false;return finish(source.equals(actuator.taskWorld().position())?"cleanup_completed":"cleanup_destination_not_reached",actuator);
        }
        return null;
    }
    public void interrupt(HumanActuator actuator){
        if(moving)actuator.stopMoving();moving=false;
        if(body!=null)actuator.corpses().release(body.token());
        // Defense may have moved the actor away: return to the body before hauling again.
        claimed=false;hauling=false;goal=null;
    }
    private void clear(HumanActuator actuator){
        interrupt(actuator);
        body=null;hauling=false;goal=null;
    }
    private String finish(String reason,HumanActuator actuator){clear(actuator);result=reason;return reason;}
}
