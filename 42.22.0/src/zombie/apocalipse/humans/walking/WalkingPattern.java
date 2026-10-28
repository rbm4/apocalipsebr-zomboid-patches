package zombie.apocalipse.humans.walking;

import java.util.List;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Destination progression and navigation scope, independent of actors, packets and traversal mechanics. */
public interface WalkingPattern {
    interface World extends MovementGrid { boolean sameBuilding(Tile first, Tile second); }
    record Context(World world, Tile current, Tile home, int z, long sequence) { }
    MovementGrid scope(Context context);
    List<Tile> plan(Context context);
    default void completed(boolean success) { }
}
