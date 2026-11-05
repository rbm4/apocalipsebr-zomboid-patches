package zombie.apocalipse.humans.healing;

import zombie.apocalipse.humans.brain.*;

/** Selected-module policy. Survival can compose a fresh instance for its recovery phase. */
public final class HealingModule implements HumanModule {
    private final HealingSettings settings;
    private HumanHealingPort port=HumanHealingPort.UNSUPPORTED;
    private HumanHealingPort.Execution execution;
    private boolean stopped, suspended;
    private long retry;
    public HealingModule(HealingSettings settings){this.settings=java.util.Objects.requireNonNull(settings);}
    public void bind(HumanHealingPort value){port=java.util.Objects.requireNonNull(value);}
    public boolean available(){return !stopped;}
    public static boolean safe(HumanObservation o,int radius) {
        var frame=o.awareness();long age=o.nowNanos()-frame.sampledAtNanos();
        return !o.state().dead()&&!o.state().injuries().downed()&&!o.state().injuries().immobile()
            &&frame.sampled()&&age>=0&&age<=500_000_000L&&frame.zombies()!=HumanSenses.Coverage.UNAVAILABLE&&!frame.contactsTruncated()
            &&frame.contacts().stream().noneMatch(c->c.identity().kind()==HumanSenses.Kind.ZOMBIE
                &&o.nowNanos()>=c.lastSeenNanos()&&o.nowNanos()-c.lastSeenNanos()<=2_000_000_000L
                &&c.distanceSquared()<=radius*(double)radius);
    }
    public boolean eligible(HumanObservation o) {
        var s=port.snapshot();long age=o.nowNanos()-s.sampledAt();
        return !stopped&&!suspended&&s.available()&&age>=0&&age<=500_000_000L
            &&safe(o,settings.clearTiles())&&(execution!=null||s.wounded()&&s.supply()||s.recovering());
    }
    public void update(HumanObservation o,HumanActuator actuator) {
        if(!eligible(o)){interrupt(o.nowNanos(),actuator);return;}
        actuator.stopMoving();actuator.tactical(TacticalIntent.NONE);
        if(execution==null&&o.nowNanos()>=retry)execution=port.begin(o.nowNanos());
        if(execution!=null&&execution.update(o.nowNanos())!=HumanHealingPort.Status.RUNNING){
            execution=null;retry=o.nowNanos()+500_000_000L;
        }
    }
    public void interrupt(long now,HumanActuator actuator){if(execution!=null)execution.cancel();execution=null;}
    public void suspend(long now,HumanActuator actuator){suspended=true;interrupt(now,actuator);}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;retry=now;}
    public void stop(long now,HumanActuator actuator){stopped=true;interrupt(now,actuator);}
}
