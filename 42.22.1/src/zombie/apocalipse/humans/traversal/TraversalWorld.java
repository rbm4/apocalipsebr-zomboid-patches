package zombie.apocalipse.humans.traversal;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;

/** Shared by the factory's active presentations; detached humans are absent from native square membership. */
public final class TraversalWorld {
    final CrossingReservations reservations = new CrossingReservations();
    private final Set<IsoPlayer> actors = Collections.newSetFromMap(new IdentityHashMap<>());
    public void add(IsoPlayer actor) { actors.add(actor); }
    public void remove(IsoPlayer actor) { actors.remove(actor); }
    boolean occupied(IsoPlayer self, IsoGridSquare square) {
        for (IsoPlayer actor : actors) {
            if (actor != self && !actor.isDead() && actor.getZi() == square.z
                && actor.getXi() == square.x && actor.getYi() == square.y) return true;
        }
        return false;
    }
}
