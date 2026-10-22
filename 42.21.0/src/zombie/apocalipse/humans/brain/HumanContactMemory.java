package zombie.apocalipse.humans.brain;

import java.util.*;
import static zombie.apocalipse.humans.brain.HumanSenses.*;

/** Per-actor short-term memory; explicit time/invalidations, no engine reads or decisions. */
public final class HumanContactMemory {
    public static final int CAPACITY=128;
    public static final long RETENTION_NANOS=3_000_000_000L;
    private final Map<Identity,Contact> contacts=new LinkedHashMap<>();
    public Frame update(long now,List<Sighting> sightings,Set<Identity> invalid,
                        Coverage players,Coverage zombies,boolean truncated,Work work){
        contacts.entrySet().removeIf(e->invalid.contains(e.getKey())||now-e.getValue().lastSeenNanos()>=RETENTION_NANOS);
        contacts.replaceAll((id,c)->new Contact(id,c.x(),c.y(),c.z(),c.distanceSquared(),c.firstSeenNanos(),c.lastSeenNanos(),false));
        for(var s:sightings){
            if(invalid.contains(s.identity()))continue;
            var old=contacts.get(s.identity());
            contacts.put(s.identity(),new Contact(s.identity(),s.x(),s.y(),s.z(),s.distanceSquared(),
                old==null?now:old.firstSeenNanos(),now,true));
        }
        var ordered=contacts.values().stream().sorted(Comparator.comparing(Contact::visible).reversed()
            .thenComparing(Comparator.comparingLong(Contact::lastSeenNanos).reversed())
            .thenComparingDouble(Contact::distanceSquared).thenComparingLong(c->c.identity().generation())).toList();
        truncated|=ordered.size()>CAPACITY;
        var kept=ordered.subList(0,Math.min(CAPACITY,ordered.size()));
        contacts.clear();for(var c:kept)contacts.put(c.identity(),c);
        return new Frame(true,now,kept,players,zombies,truncated,work);
    }
    public void clear(){contacts.clear();}
}
