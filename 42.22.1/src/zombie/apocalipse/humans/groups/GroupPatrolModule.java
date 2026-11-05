package zombie.apocalipse.humans.groups;

import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.walking.MovementRequest;

/** Each member owns only its movement execution; the shared coordinator owns waypoint progression. */
public final class GroupPatrolModule implements HumanModule {
    private GroupPatrolPort port;
    private HumanActionExecution execution;
    private int waypoint=-1,failures;
    private long revision=-1;
    private long retry;
    private long startupDelay;
    private boolean suspended,stopped;
    public void bind(GroupPatrolPort port){if(this.port!=null)throw new IllegalStateException("Already bound");this.port=java.util.Objects.requireNonNull(port);}
    public void bind(GroupPatrolPort port,int slot){bind(port);startupDelay=slot*500_000_000L;}
    public boolean available(){return !stopped;}
    public boolean eligible(HumanObservation o){
        if(port==null||stopped||suspended||!o.movementAvailable())return false;
        var v=port.observe();return v.goal().available()&&o.nowNanos()>=v.sampledAt()&&o.nowNanos()-v.sampledAt()<=500_000_000L;
    }
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o))return;
        var v=port.observe();long now=o.nowNanos();
        if(v.hold()||v.goal().dwelling()){cancel(actuator);return;}
        if(waypoint!=v.goal().waypoint()||revision!=v.revision()){boolean initial=waypoint<0;cancel(actuator);waypoint=v.goal().waypoint();revision=v.revision();failures=0;retry=now+(initial?startupDelay:0);}
        var destination=failures>=2?v.goal().center():v.goal().destination();
        if(v.inside()&&v.z()==destination.z()&&Math.hypot(v.x()-(destination.x()+.5),v.y()-(destination.y()+.5))<=1.25){cancel(actuator);return;}
        if(execution==null&&now>=retry)execution=new HumanActionExecution(new HumanAction.Move(
            new MovementRequest.To(destination,"background",true,v.regroup()||!v.inside(),v.companions())));
        if(execution!=null){
            execution.update(now,actuator);
            if(execution.terminal()){
                boolean success=execution.status()==HumanActionExecution.Status.SUCCEEDED;execution=null;
                if(!success)failures++;retry=now+(success?100_000_000L:2_000_000_000L);
            }
        }
    }
    private void cancel(HumanActuator actuator){if(execution!=null)execution.cancel(actuator);execution=null;}
    public void interrupt(long now,HumanActuator actuator){cancel(actuator);retry=now;}
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;retry=now;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
