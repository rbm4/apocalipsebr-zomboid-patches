package zombie.apocalipse.humans.encounters;

import java.util.*;
import zombie.apocalipse.humans.brain.*;

/** Per-actor, activation-local retaliation. Only authenticated attack intake may assign an aggressor. */
public final class RetaliatePlayerModule implements HumanModule {
    public record Settings(long durationMs,long lostGraceMs,long retryMs) {
        public Settings {if(durationMs<1000||durationMs>120000||lostGraceMs<0||lostGraceMs>5000||retryMs<100||retryMs>10000)throw new IllegalArgumentException("Invalid retaliation settings");}
        public static Settings from(Map<String,Long> values){
            if(!Set.of("durationMs","lostGraceMs","retryMs").containsAll(values.keySet()))throw new IllegalArgumentException("Unknown retaliation parameter");
            return new Settings(values.getOrDefault("durationMs",30000L),values.getOrDefault("lostGraceMs",2000L),values.getOrDefault("retryMs",1000L));
        }
    }
    private final Settings settings;
    private RetaliationPort port;
    private HumanObservation.Identity aggressor,executing;
    private long until,lastSeen,retry;
    private HumanCombat.Execution execution;
    private boolean suspended,stopped;
    public RetaliatePlayerModule(Settings settings){this.settings=Objects.requireNonNull(settings);}
    public void bind(RetaliationPort value){if(port!=null)throw new IllegalStateException("Already bound");port=Objects.requireNonNull(value);}
    public void attacked(HumanObservation.Identity identity,long now){if(stopped)return;aggressor=Objects.requireNonNull(identity);until=now+settings.durationMs()*1000000L;lastSeen=now;retry=now;}
    public HumanObservation.Identity aggressor(){return aggressor;}
    public boolean available(){return !stopped;}
    public boolean eligible(HumanObservation o){return !stopped&&!suspended&&port!=null&&aggressor!=null;}
    public boolean emergency(HumanObservation o){return !stopped&&port!=null&&aggressor!=null&&o.nowNanos()<until&&port.observe(aggressor).valid();}
    public void update(HumanObservation o,HumanActuator actuator){
        if(!eligible(o))return;long now=o.nowNanos();
        var contact=port.observe(aggressor);
        if(now>=until||!contact.valid()){clear();return;}
        if(!contact.visible()){
            cancel();if(now-lastSeen>=settings.lostGraceMs()*1000000L)clear();return;
        }
        lastSeen=now;
        if(!Objects.equals(executing,aggressor))cancel();
        if(execution==null&&now>=retry){executing=aggressor;execution=port.begin(aggressor,now);}
        if(execution!=null&&execution.update(now).terminal()){execution=null;executing=null;retry=now+settings.retryMs()*1000000L;}
    }
    private void cancel(){if(execution!=null)execution.cancel(HumanCombat.Reason.STOPPED);execution=null;executing=null;}
    private void clear(){cancel();aggressor=null;}
    public void interrupt(long now,HumanActuator actuator){cancel();}
    public void suspend(long now,HumanActuator actuator){cancel();suspended=true;}
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator){suspended=false;}
    public void stop(long now,HumanActuator actuator){clear();stopped=true;}
}
