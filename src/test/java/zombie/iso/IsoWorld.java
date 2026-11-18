package zombie.iso;

import java.util.ArrayList;
import java.util.List;

/** Structural double for the telemetry world-gauge test; never packaged. */
public final class IsoWorld {
    public static final IsoWorld instance = new IsoWorld();
    public Cell currentCell;

    public static final class Cell {
        public final List<Object> zombies = new ArrayList<>();

        public List<Object> getZombieList() {
            return this.zombies;
        }
    }
}
