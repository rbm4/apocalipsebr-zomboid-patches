package zombie.apocalipse.humans;

import java.util.Locale;
import java.util.function.Supplier;
import zombie.apocalipse.humans.state.HumanState;
import zombie.apocalipse.humans.state.HumanStateStore;
import zombie.debug.DebugLog;

/** NPC-wide diagnostic threshold, configured once at JVM startup. */
public final class HumanLog {
    public enum Level { DEBUG, INFO, WARN, ERROR, OFF }
    private static final Level THRESHOLD=configuredLevel();
    private HumanLog() { }
    private static Level configuredLevel(){
        try{return Level.valueOf(System.getProperty("apocbr.npc.logLevel","ERROR").trim().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException ignored){return Level.ERROR;}
    }
    public static boolean enabled(Level level){return level!=Level.OFF&&level.ordinal()>=THRESHOLD.ordinal();}
    public static void log(Level level,Supplier<String> message){
        if(enabled(level))DebugLog.log("[ApocBR][NPC]["+level+"] "+message.get());
    }
    /** The state package depends only on this injected observer, never on native logging. */
    static HumanStateStore.Observer mutations(String key){
        return (previous,mutation,next)->{
            if(!enabled(Level.DEBUG))return;
            StringBuilder changes=new StringBuilder();
            var a=previous.vitals();var b=next.vitals();
            change(changes,"health",a.health(),b.health());
            change(changes,"endurance",a.endurance(),b.endurance());
            change(changes,"hunger",a.hunger(),b.hunger());
            change(changes,"thirst",a.thirst(),b.thirst());
            change(changes,"fatigue",a.fatigue(),b.fatigue());
            change(changes,"pain",a.pain(),b.pain());
            change(changes,"cold",a.cold(),b.cold());
            change(changes,"fear",a.fear(),b.fear());
            change(changes,"sickness",a.sickness(),b.sickness());
            change(changes,"dirtiness",a.dirtiness(),b.dirtiness());
            change(changes,"infection",a.infection(),b.infection());
            var x=previous.injuries();var y=next.injuries();
            change(changes,"leg",x.leg(),y.leg());change(changes,"arms",x.arms(),y.arms());
            change(changes,"bleedingPerSecond",x.bleedingPerSecond(),y.bleedingPerSecond());
            if(x.immobile()!=y.immobile())changes.append("; immobile=").append(x.immobile()).append(" -> ").append(y.immobile());
            if(x.downed()!=y.downed())changes.append("; downed=").append(x.downed()).append(" -> ").append(y.downed());
            log(Level.DEBUG,()->"mutation key="+key+"; type="+mutation.getClass().getSimpleName()
                +"; revision="+previous.revision()+" -> "+next.revision()+changes);
        };
    }
    private static void change(StringBuilder out,String name,float previous,float next){
        if(previous!=next)out.append("; ").append(name).append('=').append(previous).append(" -> ").append(next);
    }
}
