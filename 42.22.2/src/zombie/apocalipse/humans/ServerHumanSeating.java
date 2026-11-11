package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.HumanActuator.MotionStatus;
import zombie.apocalipse.humans.furniture.FurnitureAffordances.*;
import zombie.apocalipse.humans.seating.*;
import zombie.apocalipse.humans.seating.HumanSeating.*;
import zombie.apocalipse.humans.seating.SeatingGeometry.Plan;
import zombie.apocalipse.humans.walking.*;
import zombie.iso.*;

/** Per-actor native posture capability. The factory ticks it even after action cancellation. */
final class ServerHumanSeating implements HumanSeating {
    private final ServerHuman human;private final ServerHumanFurniture furniture;
    private final ServerHumanSeatingGeometry geometry;
    final ServerHumanSeatingTransport transport;
    private SeatingExecution active;
    private Session session;
    private long lastTick=Long.MIN_VALUE,epoch;
    private boolean closed;
    ServerHumanSeating(ServerHuman human,ServerHumanFurniture furniture){this.human=human;this.furniture=furniture;geometry=new ServerHumanSeatingGeometry(human,furniture);transport=new ServerHumanSeatingTransport(human,this);}
    public Execution begin(Request request,long now){
        human.requireAuthority();Objects.requireNonNull(request);
        if(closed||active!=null||!ServerHumanSeatingAssets.supported()||human.state.snapshot().dead()||human.movement.traversing())return null;
        furniture.retain(human);
        try{
            var plan=geometry.resolve(request.target(),request.side(),now);var object=furniture.resolve(request.target());
            if(plan==null||object==null||geometry.occupied(object)){furniture.release(human);return null;}
            var next=new Session(plan,object,human.state.snapshot().vitals().health());
            var execution=new SeatingExecution(human.incarnation,furniture.reservations,request,next,now);
            if(execution.status()!=Status.RUNNING){furniture.release(human);return null;}
            session=next;active=execution;epoch++;lastTick=Long.MIN_VALUE;
            return new Execution(){
                public Status update(long at){human.requireAuthority();if(active==execution)tick(at);return execution.status();}
                public void cancel(){human.requireAuthority();if(active==execution)execution.cancel();}
            };
        }catch(RuntimeException failure){furniture.release(human);throw failure;}
    }
    void tick(long now){
        human.requireAuthority();
        if(now<=lastTick)return;lastTick=now;
        if(active!=null){
            var execution=active;
            try{execution.update(now);}catch(RuntimeException failure){HumanLog.log(HumanLog.Level.ERROR,()->"seating failed key="+human.definition.key()+"; issue="+failure.getClass().getSimpleName());}
            finally{if(execution.status()!=Status.RUNNING){active=null;session=null;furniture.release(human);}}
        }
        transport.tick(now);
    }
    boolean ownsPose(){return active!=null&&active.ownsPose();}
    boolean transitioning(){return active!=null&&(active.phase()==Phase.SIT_DOWN||active.phase()==Phase.STAND_UP);}
    boolean readyForMotion(){human.requireAuthority();if(ownsPose()){active.cancel();return false;}return true;}
    Phase phase(){return active==null?Phase.FINISHED:active.phase();}
    Plan plan(){return session==null?null:session.exit==null?session.plan:session.exit;}
    IsoObject object(){return session==null?null:session.object;}
    long epoch(){return epoch;}
    Position checkpoint(){return session!=null&&session.pose?session.plan.approach():null;}
    void close(){human.requireAuthority();closed=true;try{if(active!=null)active.abort("removed");}finally{active=null;session=null;transport.close();furniture.release(human);}}
    private Position current(){return new Position(human.player.getX(),human.player.getY(),human.player.getZi());}
    private void position(Position point){human.player.setX(point.x());human.player.setY(point.y());human.player.setZ(point.z());human.player.realx=point.x();human.player.realy=point.y();human.player.realz=(byte)point.z();}
    private final class Session implements SeatingExecution.Port {
        final Plan plan;final IsoObject object;final float health;
        Plan exit;boolean walking,pose;
        Session(Plan plan,IsoObject object,float health){this.plan=plan;this.object=object;this.health=health;}
        public boolean valid(){
            var state=human.state.snapshot();
            return !closed&&!state.dead()&&!state.injuries().downed()&&!state.injuries().immobile()
                &&state.vitals().health()>=health&&furniture.resolve(plan.target())==object&&geometry.sameRoom(object)&&!geometry.occupied(object);
        }
        public SeatingExecution.Motion approach(long now){
            if(!walking){
                human.actuator.stopEmote();human.actuator.tactical(zombie.apocalipse.humans.brain.TacticalIntent.NONE);
                var destination=new WalkingPatternSpec.Point((int)Math.floor(plan.approach().x()),(int)Math.floor(plan.approach().y()),plan.approach().z());
                if(!human.walking.start(new MovementRequest.To(destination),now))return SeatingExecution.Motion.FAILED;
                walking=true;
            }
            var status=human.walking.update(now);
            if(status==MotionStatus.FAILED)return SeatingExecution.Motion.FAILED;
            if(status==MotionStatus.RUNNING)return SeatingExecution.Motion.RUNNING;
            var from=current();
            if(SeatingGeometry.distance(from,plan.approach())>.85||!geometry.free(plan.approach())
                ||zombie.pathfind.PolygonalMap2.instance.lineClearCollide(from.x(),from.y(),plan.approach().x(),plan.approach().y(),from.z(),human.player,false,true)
                ||!geometry.bodies(from,plan.approach()))return SeatingExecution.Motion.FAILED;
            human.walking.stop();position(plan.approach());return SeatingExecution.Motion.SUCCEEDED;
        }
        public void sit(){
            if(!valid()||!geometry.passage(plan.approach(),plan.entry())||!geometry.bodies(current(),plan.entry()))throw new IllegalStateException("Seat changed before commit");
            human.walking.stop();position(plan.entry());pose=true;
            object.setSatChair(true);human.player.setSitOnFurnitureObject(object);human.player.setSitOnFurnitureDirection(IsoDirections.fromString(plan.facing()));
            human.player.setSittingOnFurniture(true);human.player.setVariable("SitOnFurnitureDirection",plan.side());human.player.setVariable("SitOnFurnitureStarted",false);
            human.player.setVariable("SitOnFurnitureAnim","");human.player.setForwardIsoDirection(IsoDirections.fromString(plan.facing()));
            transport.enter();
        }
        private boolean interpolate(Position start,Position end,double fraction){
            var next=new Position((float)(start.x()+(end.x()-start.x())*fraction),(float)(start.y()+(end.y()-start.y())*fraction),start.z());
            if(!geometry.passage(current(),next)||!geometry.bodies(current(),next))return false;position(next);return true;
        }
        public boolean sitting(double fraction){return interpolate(plan.entry(),plan.seat(),fraction);}
        public void seated(){position(plan.seat());human.player.setForwardIsoDirection(IsoDirections.fromString(plan.facing()));human.player.setVariable("SitOnFurnitureStarted",true);human.player.setVariable("SitOnFurnitureAnim","Idle");transport.settled();}
        public Plan exit(){
            var choices=new LinkedHashSet<String>();choices.add(plan.side());choices.addAll(List.of("Front","Left","Right"));
            for(String side:choices){var candidate=geometry.resolve(plan.target(),side,lastTick);if(candidate!=null&&geometry.free(candidate.exit())&&geometry.bodies(current(),candidate.exit()))return candidate;}
            return null;
        }
        public void stand(Plan selected){exit=selected;human.player.setVariable("SitOnFurnitureDirection",selected.side());human.player.setVariable("forceGetUp",true);transport.stand();}
        public boolean standing(double fraction){return geometry.free(exit.exit())&&interpolate(plan.seat(),exit.exit(),fraction);}
        public void end(boolean abort){
            try{
                human.walking.stop();
                if(pose){
                    boolean reaction=human.state.snapshot().dead()||human.state.snapshot().injuries().downed()
                        ||!zombie.util.StringUtils.isNullOrEmpty(human.player.getVariableString("HitReaction"));
                    if(!abort&&exit!=null)position(exit.exit());
                    else if(!closed&&!reaction){var safe=exit();if(safe!=null)position(safe.exit());}
                    transport.end(abort,reaction);
                }
            }finally{
                object.setSatChair(false);human.player.setSittingOnFurniture(false);human.player.setSitOnFurnitureObject(null);human.player.setSitOnFurnitureDirection(null);
                human.player.clearVariable("SitOnFurnitureStarted");human.player.clearVariable("SitOnFurnitureAnim");human.player.clearVariable("forceGetUp");
                human.player.clearVariable("SitOnFurnitureDirection");pose=false;
            }
        }
    }
}
