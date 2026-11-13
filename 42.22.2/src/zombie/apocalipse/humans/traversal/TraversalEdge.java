package zombie.apocalipse.humans.traversal;

import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** A simulation-thread observation, never a durable promise that an opening remains usable. */
public record TraversalEdge(Tile from, Tile to, int z, Kind kind, Object obstacle) {
    public enum Kind {
        BLOCKED(0), WALK(1), DOOR(2), WINDOW(6), FENCE(5);
        private final int cost;
        Kind(int cost) { this.cost = cost; }
        public int cost() { return cost; }
        public boolean climbs() { return this == WINDOW || this == FENCE; }
    }
    public boolean usable() { return kind != Kind.BLOCKED; }
    public boolean sameOpening(TraversalEdge other) {
        return other != null && from.equals(other.from) && to.equals(other.to) && z == other.z
            && kind == other.kind && obstacle == other.obstacle;
    }
}
