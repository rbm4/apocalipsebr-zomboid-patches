package zombie.apocalipse.humans.brain;
/** Reusable background movement policy, independent of greeting or actor identity. */
public final class RoamModule implements HumanModule {
    private final long pause;
    private long next;
    private HumanActionExecution execution;
    private boolean suspended,stopped;
    public RoamModule(long pause){if(pause<0||pause>86400000000000L)throw new IllegalArgumentException("Invalid roam pause");this.pause=pause;}
    public boolean eligible(HumanObservation observation){return !suspended&&!stopped&&observation.canRoam();}
    public boolean available(){return !stopped;}
    public void update(HumanObservation observation,HumanActuator actuator) {
        if(suspended||stopped)return;
        long now=observation.nowNanos();
        if(next==0){next=now+pause;return;}
        if(now<next)return;
        if(execution==null)execution=new HumanActionExecution(new HumanAction.Roam());
        execution.update(now,actuator);
        if(execution.terminal()){execution=null;next=now+pause;}
    }
    public void interrupt(long now,HumanActuator actuator){if(execution!=null)execution.cancel(actuator);execution=null;next=now+pause;}
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;next=now+pause;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
