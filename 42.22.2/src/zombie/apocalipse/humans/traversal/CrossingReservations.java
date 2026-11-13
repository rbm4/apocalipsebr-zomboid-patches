package zombie.apocalipse.humans.traversal;

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
    private final zombie.apocalipse.humans.interactions.InteractionReservations<Object> shared =
        new zombie.apocalipse.humans.interactions.InteractionReservations<>(256);
    private final Map<Lease, zombie.apocalipse.humans.interactions.InteractionReservations.Lease<Object>> leases = new java.util.IdentityHashMap<>();
    private long sequence;

    public Lease acquire(Object owner, TraversalEdge edge, long now) {
        Key key = Key.of(edge);
        Place landing = new Place(edge.to(), edge.z());
        leases.entrySet().removeIf(e -> !shared.owns(e.getValue(), now));
        var claimed = shared.acquire(owner, java.util.List.of(key, landing), now, 9_000_000_000L);
        if (claimed == null) return null;
        Lease lease = new Lease(owner, ++sequence, key, landing, now + 9_000_000_000L);
        leases.put(lease, claimed);
        return lease;
    }
    public boolean owns(Lease lease, long now) {
        return lease != null && shared.owns(leases.get(lease), now);
    }
    public void release(Lease lease) {
        if (lease == null) return;
        shared.release(leases.remove(lease));
    }
}
