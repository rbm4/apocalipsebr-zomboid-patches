package zombie.apocalipse.humans.speech;

import java.util.*;

/** Bounded semantic memory; simulation mutates, worker stages detached absolute snapshots. */
public final class HumanSpeechMemory {
    public static final long ATTACK_TTL_MS=86_400_000L;
    public static final int CAPACITY=100000,PENDING_LIMIT=4096,RECENT_LIMIT=40;
    public record Key(String npc,String kind,String account) {
        public Key {if(npc==null||!npc.matches("[A-Za-z0-9_.-]{1,128}")||!("npc".equals(kind)||"steam".equals(kind)||"username".equals(kind))
            ||account==null||account.isBlank()||account.length()>128||("npc".equals(kind)&&!account.equals("_")))throw new IllegalArgumentException("Invalid speech memory key");}
    }
    public record State(long revision,long lastAttackMs,long attacks,List<String> recent) {
        public State {
            recent=List.copyOf(recent);
            if(revision<1||lastAttackMs<0||attacks<0||recent.size()>RECENT_LIMIT||recent.stream().anyMatch(id->id==null||!id.matches("[A-Za-z0-9_.-]{1,128}")))
                throw new IllegalArgumentException("Invalid speech memory");
        }
    }
    public record Change(Key key,State state) { }
    public record Attack(boolean accepted,boolean repeated) { }
    private final Map<Key,State> values=new HashMap<>();
    private final Map<Key,State> pending=new LinkedHashMap<>();
    private long revision;
    public synchronized void restore(Map<Key,State> restored){
        if(restored.size()>CAPACITY||!pending.isEmpty())throw new IllegalStateException("Invalid speech restore");
        values.clear();values.putAll(restored);revision=restored.values().stream().mapToLong(State::revision).max().orElse(0);
    }
    public synchronized State get(Key key){return values.get(key);}
    private boolean room(Key key){return (values.containsKey(key)||values.size()<CAPACITY)&&(pending.containsKey(key)||pending.size()<PENDING_LIMIT);}
    public synchronized Attack attacked(Key key,long at){
        if(at<0)throw new IllegalArgumentException("Invalid attack time");
        var old=values.get(key);
        // A backward wall-clock adjustment must not manufacture a new first hit.
        boolean repeated=old!=null&&old.attacks()>0&&at-old.lastAttackMs()<ATTACK_TTL_MS;
        if(!room(key))return new Attack(false,repeated);
        var state=new State(Math.incrementExact(revision),Math.max(at,old==null?0:old.lastAttackMs()),repeated?Math.addExact(old.attacks(),1):1,List.of());
        revision=state.revision();values.put(key,state);pending.put(key,state);return new Attack(true,repeated);
    }
    public synchronized boolean remember(Key key,String id){
        if(!room(key))return false;
        var old=values.get(key);var ids=new ArrayList<String>(old==null?List.of():old.recent());
        ids.remove(id);ids.add(id);while(ids.size()>RECENT_LIMIT)ids.removeFirst();
        var state=new State(Math.incrementExact(revision),old==null?0:old.lastAttackMs(),old==null?0:old.attacks(),ids);
        revision=state.revision();values.put(key,state);pending.put(key,state);return true;
    }
    public synchronized List<Change> pending(){return pending.entrySet().stream().map(e->new Change(e.getKey(),e.getValue())).toList();}
    public synchronized void staged(Change value){pending.remove(value.key(),value.state());}
}
