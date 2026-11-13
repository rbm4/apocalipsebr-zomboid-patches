package zombie.apocalipse.humans.traversal;

import java.util.HashMap;
import java.util.Map;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Factory-owned, simulation-thread-only leases. Both directions share one crossing. */
public final class CrossingReservations {
    public record Place(Tile tile, int z) { }
    public record Key(Place a, Place b) {
        public static Key of(TraversalEdge edge) {
            Tile a = edge.from(), b = edge.to();
            if (a.x() > b.x() || a.x() == b.x() && a.y() > b.y()) { Tile swap = a; a = b; b = swap; }
            return new Key(new Place(a, edge.z()), new Place(b, edge.z()));
        }
    }
    public record Lease(Object owner, long token, Key key, Place landing, long until) { }
    private final Map<Key, Lease> crossings = new HashMap<>();
    private final Map<Place, Lease> landings = new HashMap<>();
    private long sequence;

    public Lease acquire(Object owner, TraversalEdge edge, long now) {
        Key key = Key.of(edge);
        Place landing = new Place(edge.to(), edge.z());
        Lease crossing = crossings.get(key), arrival = landings.get(landing);
        if (live(crossing, now) || live(arrival, now)) return null;
        release(crossing); release(arrival);
        Lease lease = new Lease(owner, ++sequence, key, landing, now + 9_000_000_000L);
        crossings.put(key, lease); landings.put(landing, lease);
        return lease;
    }
    public boolean owns(Lease lease, long now) {
        return live(lease, now) && crossings.get(lease.key()) == lease && landings.get(lease.landing()) == lease;
    }
    public void release(Lease lease) {
        if (lease == null) return;
        crossings.remove(lease.key(), lease); landings.remove(lease.landing(), lease);
    }
    private static boolean live(Lease lease, long now) { return lease != null && now < lease.until(); }
}
