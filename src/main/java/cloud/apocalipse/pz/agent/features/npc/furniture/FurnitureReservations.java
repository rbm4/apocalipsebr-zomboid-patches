package cloud.apocalipse.pz.agent.features.npc.furniture;

import java.util.List;
import cloud.apocalipse.pz.agent.features.npc.interactions.InteractionReservations;
import cloud.apocalipse.pz.agent.features.npc.furniture.FurnitureAffordances.Token;

/** Uses traversal's shared lease primitive; whole-object occupancy is conservative for vanilla. */
public final class FurnitureReservations {
    private record ObjectKey(long generation,int x,int y,int z) { }
    private final InteractionReservations<ObjectKey> leases=new InteractionReservations<>(64);
    public static final class Lease {
        private final InteractionReservations.Lease<ObjectKey> delegate;
        private Lease(InteractionReservations.Lease<ObjectKey> delegate){this.delegate=delegate;}
    }
    public Lease acquire(Object incarnation,Token target,long now) {
        var key=new ObjectKey(target.generation(),target.x(),target.y(),target.z());
        var claim=leases.acquire(incarnation,List.of(key),now,5_000_000_000L);
        return claim==null?null:new Lease(claim);
    }
    public boolean renew(Lease lease,long now){return lease!=null&&leases.renew(lease.delegate,now,5_000_000_000L);}
    public boolean owns(Lease lease,long now){return lease!=null&&leases.owns(lease.delegate,now);}
    public void release(Lease lease){if(lease!=null)leases.release(lease.delegate);}
    public void releaseOwner(Object incarnation){leases.releaseOwner(incarnation);}
}
