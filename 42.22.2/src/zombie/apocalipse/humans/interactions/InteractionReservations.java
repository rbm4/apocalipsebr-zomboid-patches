package zombie.apocalipse.humans.interactions;

import java.util.*;

/** Owner-thread-only, bounded atomic leases over typed resources. Policy stays in callers. */
public final class InteractionReservations<K> {
    public static final class Lease<K> {
        private final Object owner;
        private final List<K> resources;
        private long until;
        private Lease(Object owner,List<K> resources,long until) {
            this.owner=owner;this.resources=resources;this.until=until;
        }
        public Object owner(){return owner;}
        public List<K> resources(){return resources;}
        public long until(){return until;}
    }
    // The factory can be constructed before its server owner is initialized.
    // Bind on the first operation, not during class loading/composition.
    private volatile Thread authority;
    private final int capacity;
    private final Map<K,Lease<K>> held=new HashMap<>();
    public InteractionReservations(int capacity) {
        if(capacity<1||capacity>4096)throw new IllegalArgumentException("Invalid reservation capacity");
        this.capacity=capacity;
    }
    private void check(){
        var thread=Thread.currentThread();
        if(authority==null)synchronized(this){if(authority==null)authority=thread;}
        if(thread!=authority)throw new IllegalStateException("Interaction lease requires owner thread");
    }
    private static long deadline(long now,long duration) {
        if(duration<=0||duration>60_000_000_000L)throw new IllegalArgumentException("Invalid lease duration");
        return Math.addExact(now,duration);
    }
    public Lease<K> acquire(Object owner,List<K> resources,long now,long duration) {
        check();Objects.requireNonNull(owner);
        var keys=List.copyOf(resources);
        if(keys.isEmpty()||keys.size()>4||new HashSet<>(keys).size()!=keys.size())throw new IllegalArgumentException("Invalid lease resources");
        long until=deadline(now,duration);
        // Expired leases cannot grow the map or partially block an atomic acquisition.
        held.entrySet().removeIf(e->now>=e.getValue().until);
        for(K key:keys)if(held.containsKey(key))return null;
        if(held.size()+keys.size()>capacity)return null;
        var lease=new Lease<>(owner,keys,until);
        for(K key:keys)held.put(key,lease);
        return lease;
    }
    public boolean owns(Lease<K> lease,long now) {
        check();return lease!=null&&now<lease.until&&lease.resources.stream().allMatch(k->held.get(k)==lease);
    }
    public boolean renew(Lease<K> lease,long now,long duration) {
        check();long until=deadline(now,duration);
        if(!owns(lease,now))return false;
        lease.until=Math.max(lease.until,until);return true;
    }
    public void release(Lease<K> lease) {
        check();if(lease!=null)for(K key:lease.resources)held.remove(key,lease);
    }
    public void releaseOwner(Object owner) {
        check();held.entrySet().removeIf(e->e.getValue().owner==owner);
    }
    public int resources(){check();return held.size();}
}
