package zombie.iso;

import java.util.ArrayList;
import java.util.List;

/** Structural double for the telemetry world-gauge test; never packaged. */
public final class IsoWorld {
    public static final IsoWorld instance = new IsoWorld();
    public Cell currentCell;

    public static final class Cell {
        private final java.util.Set<Object> objects=new cloud.apocalipse.pz.agent.features.npc.NpcMembershipSet<>(new java.util.HashSet<>());
        public java.util.Set<Object> getObjectList(){return objects;}
        public final List<Object> zombies = new ArrayList<>();

        public List<Object> getZombieList() {
            return this.zombies;
        }
    }
}
