package zombie.apocalipse.humans.brain;
/** Schedules the profile-selected background walking pattern; owns no destination or physical movement. */
public final class WalkingModule implements HumanModule {
    private final long pause;
    private long next;
    private HumanActionExecution execution;
    private boolean suspended,stopped,scheduled;
    public WalkingModule(long pause){if(pause<0||pause>86400000000000L)throw new IllegalArgumentException("Invalid roam pause");this.pause=pause;}
    public boolean eligible(HumanObservation observation){return !suspended&&!stopped&&observation.movementAvailable();}
    public boolean available(){return !stopped;}
    public void update(HumanObservation observation,HumanActuator actuator) {
        if(suspended||stopped)return;
        long now=observation.nowNanos();
        if(!scheduled){next=now+pause;scheduled=true;}
        if(now<next)return;
        if(execution==null)execution=new HumanActionExecution(new HumanAction.Move(new zombie.apocalipse.humans.walking.MovementRequest.Pattern("background")));
        execution.update(now,actuator);
        if(execution.terminal()){execution=null;next=now+pause;}
    }
    public void interrupt(long now,HumanActuator actuator){if(execution!=null)execution.cancel(actuator);execution=null;next=now+pause;scheduled=true;}
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;next=now+pause;scheduled=true;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
