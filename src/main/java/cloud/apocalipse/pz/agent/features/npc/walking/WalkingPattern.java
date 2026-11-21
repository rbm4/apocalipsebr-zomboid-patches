package cloud.apocalipse.pz.agent.features.npc.walking;

import java.util.List;
import cloud.apocalipse.pz.agent.features.npc.movement.MovementGrid;
import cloud.apocalipse.pz.agent.features.npc.movement.MovementGrid.Tile;

/** Destination progression and navigation scope, independent of actors, packets and traversal mechanics. */
public interface WalkingPattern {
    interface World extends MovementGrid { boolean sameBuilding(Tile first, Tile second); }
    record Context(World world, Tile current, Tile home, int z, long sequence) { }
    MovementGrid scope(Context context);
    List<Tile> plan(Context context);
    default void completed(boolean success) { }
}
