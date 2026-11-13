package zombie.apocalipse.humans.movement;

/** Simulation-thread traversal policy, independent of the pattern choosing a route. */
public interface MovementGrid {
    record Tile(int x, int y) { }
    default boolean available() { return true; }
    boolean walkable(Tile tile);
    boolean connected(Tile from, Tile to);
    /** Positive relative effort for a usable edge; zero means blocked. Queries never open objects. */
    default int traversalCost(Tile from, Tile to) { return connected(from, to) ? 1 : 0; }
}
