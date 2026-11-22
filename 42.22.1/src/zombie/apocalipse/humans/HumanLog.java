package zombie.apocalipse.humans;

import java.util.Locale;
import java.util.function.Supplier;
import zombie.savefile.ApocBRNpcStore;
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
    /** Emitted after a changed checkpoint is offered, not for individual state mutations. */
    static void checkpoint(ApocBRNpcStore.State previous,ApocBRNpcStore.State next){
        if(!enabled(Level.DEBUG))return;
        if(previous==null){
            log(Level.DEBUG,()->"checkpoint queued key="+next.definition().key()+"; checkpointRevision="+next.revision()
                +"; initial=true; lifecycle="+next.lifecycle()+"; state="+next.physiology());
            return;
        }
        StringBuilder changes=new StringBuilder();
        var a=previous.physiology().vitals();var b=next.physiology().vitals();
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
        var x=previous.physiology().injuries();var y=next.physiology().injuries();
        change(changes,"leg",x.leg(),y.leg());change(changes,"arms",x.arms(),y.arms());
        change(changes,"bleedingPerSecond",x.bleedingPerSecond(),y.bleedingPerSecond());
        if(x.immobile()!=y.immobile())changes.append("; immobile=").append(x.immobile()).append(" -> ").append(y.immobile());
        if(x.downed()!=y.downed())changes.append("; downed=").append(x.downed()).append(" -> ").append(y.downed());
        change(changes,"x",previous.x(),next.x());change(changes,"y",previous.y(),next.y());
        if(previous.z()!=next.z())changes.append("; z=").append(previous.z()).append(" -> ").append(next.z());
        if(previous.lifecycle()!=next.lifecycle())changes.append("; lifecycle=").append(previous.lifecycle()).append(" -> ").append(next.lifecycle());
        if(previous.activations()!=next.activations())changes.append("; activations=").append(previous.activations()).append(" -> ").append(next.activations());
        if(previous.profileRevision()!=next.profileRevision())changes.append("; profileRevision=").append(previous.profileRevision()).append(" -> ").append(next.profileRevision());
        if(previous.retired()!=next.retired())changes.append("; retired=").append(previous.retired()).append(" -> ").append(next.retired());
        log(Level.DEBUG,()->"checkpoint queued key="+next.definition().key()
            +"; checkpointRevision="+previous.revision()+" -> "+next.revision()
            +"; stateRevision="+previous.physiology().revision()+" -> "+next.physiology().revision()+changes);
    }

    private static void change(StringBuilder out,String name,float previous,float next){
        if(previous!=next)out.append("; ").append(name).append('=').append(previous).append(" -> ").append(next);
    }
}
