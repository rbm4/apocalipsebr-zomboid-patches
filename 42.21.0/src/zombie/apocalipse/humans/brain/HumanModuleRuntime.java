package zombie.apocalipse.humans.brain;
import java.util.*;
/** Shared bounded scheduler; exclusive interactions remain owned by HumanActivityController. */
public final class HumanModuleRuntime implements HumanBehavior {
    public record Entry(String id,int priority,int cycleMs,HumanModule module) {
        public Entry {
            zombie.apocalipse.humans.profiles.HumanProfile.identifier(id);Objects.requireNonNull(module);
            if(priority<0||priority>10000||cycleMs<100||cycleMs>60000)throw new IllegalArgumentException("Invalid module scheduling");
        }
    }
    private final List<Entry> entries;
    private final Map<String,Long> nextCycles=new HashMap<>();
    private Entry selected;
    private boolean suspended,stopped;
    public HumanModuleRuntime(List<Entry> entries) {
        if(entries.size()>16)throw new IllegalArgumentException("Too many modules");
        this.entries=entries.stream().sorted(Comparator.comparingInt(Entry::priority).thenComparing(Entry::id)).toList();
        if(this.entries.stream().map(Entry::id).distinct().count()!=entries.size())throw new IllegalArgumentException("Duplicate modules");
    }
    public boolean needsPerception(){return !entries.isEmpty();}
    public boolean available(){return !stopped;}
    public boolean emergency(HumanObservation observation){
        return !stopped&&entries.stream().anyMatch(entry->entry.module().emergency(observation));
    }
    public void update(HumanObservation observation,HumanActuator actuator) {
        if(stopped||suspended)return;
        long now=observation.nowNanos();
        Entry winner=null;
        for(Entry entry:entries) {
            if(entry==selected) {
                if(entry.module().available()&&entry.module().eligible(observation)){winner=entry;break;}
            } else if(now>=nextCycles.getOrDefault(entry.id(),Long.MIN_VALUE)) {
                nextCycles.put(entry.id(),now+entry.cycleMs()*1000000L);
                if(entry.module().available()&&entry.module().eligible(observation)){winner=entry;break;}
            }
        }
        if(selected!=winner) {
            actuator.moduleChanged(selected==null?"none":selected.id(),winner==null?"none":winner.id());
            if(selected!=null)selected.module().interrupt(now,actuator);
            selected=winner;
        }
        if(selected!=null)selected.module().update(observation,actuator);
    }
    public String selectedModule(){return selected==null?null:selected.id();}
    /** Capability discovery at composition/command boundaries, never an actor-key lookup. */
    public <T extends HumanModule> T module(Class<T> type){
        return entries.stream().map(Entry::module).filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }
    public void suspend(long now,HumanActuator actuator) {
        if(stopped||suspended)throw new IllegalStateException("Runtime cannot suspend");
        suspended=true;
        for(Entry entry:entries)entry.module().suspend(now,actuator);
        selected=null;
    }
    public void resume(HumanObservation.Identity visitor,long now,HumanActuator actuator) {
        if(!suspended||stopped)return;
        for(Entry entry:entries)entry.module().resume(visitor,now,actuator);
        suspended=false;nextCycles.clear();
    }
    public void stop(long now,HumanActuator actuator) {
        if(stopped)return;stopped=true;selected=null;
        RuntimeException failure=null;
        for(Entry entry:entries)try{entry.module().stop(now,actuator);}catch(RuntimeException ex){failure=ex;}
        if(failure!=null)throw failure;
    }
}
