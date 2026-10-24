package zombie.apocalipse.humans.brain;

import java.util.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;

/** Per-actor short-term memory; explicit time/invalidations, no engine reads or decisions. */
public final class HumanContactMemory {
    public static final int CAPACITY=128;
    public static final long RETENTION_NANOS=3_000_000_000L;
    private final Map<Identity,Contact> contacts=new LinkedHashMap<>();
    private final Map<Identity,Long> relevantAt=new HashMap<>();
    public void invalidate(Set<Identity> invalid){contacts.keySet().removeAll(invalid);relevantAt.keySet().removeAll(invalid);}
    public Frame update(long now,List<Sighting> sightings,Set<Identity> invalid,
                        Coverage players,Coverage zombies,boolean truncated,Work work){
        return update(now,sightings,invalid,Set.of(),players,zombies,truncated,work);
    }
    /** Relevant live identities retain last-seen facts without acquiring visual authority. */
    public Frame update(long now,List<Sighting> sightings,Set<Identity> invalid,Set<Identity> relevant,
                        Coverage players,Coverage zombies,boolean truncated,Work work){
        invalidate(invalid);
        for(var id:relevant)if(contacts.containsKey(id)&&!invalid.contains(id))relevantAt.put(id,now);
        contacts.replaceAll((id,c)->new Contact(id,c.x(),c.y(),c.z(),c.distanceSquared(),c.firstSeenNanos(),c.lastSeenNanos(),false));
        for(var s:sightings){
            if(invalid.contains(s.identity()))continue;
            relevantAt.put(s.identity(),now);
            var old=contacts.get(s.identity());
            contacts.put(s.identity(),new Contact(s.identity(),s.x(),s.y(),s.z(),s.distanceSquared(),
                old==null?now:old.firstSeenNanos(),now,true));
        }
        contacts.entrySet().removeIf(e->now-relevantAt.getOrDefault(e.getKey(),e.getValue().lastSeenNanos())>=RETENTION_NANOS);
        var ordered=contacts.values().stream().sorted(Comparator.comparing(Contact::visible).reversed()
            .thenComparing(Comparator.comparingLong(Contact::lastSeenNanos).reversed())
            .thenComparingDouble(Contact::distanceSquared).thenComparingLong(c->c.identity().generation())).toList();
        truncated|=ordered.size()>CAPACITY;
        var kept=ordered.subList(0,Math.min(CAPACITY,ordered.size()));
        contacts.clear();for(var c:kept)contacts.put(c.identity(),c);
        relevantAt.keySet().retainAll(contacts.keySet());
        return new Frame(true,now,kept,players,zombies,truncated,work);
    }
    public void clear(){contacts.clear();relevantAt.clear();}
}
