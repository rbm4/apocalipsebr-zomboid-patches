package zombie.apocalipse.humans.furniture;

import java.util.*;
import static zombie.apocalipse.humans.furniture.FurnitureAffordances.*;

/** Pure candidate choice. A reservation and live-world validation are still required. */
public final class FurnitureSelector {
    private FurnitureSelector() { }
    public record Choice(Candidate candidate,Approach approach) { }
    public static Choice choose(Frame frame,Kind kind,Position observer,long now,Set<Token> refused) {
        if(!frame.fresh(now,2_000_000_000L))return null;
        return frame.candidates().stream().filter(c->c.kind()==kind&&c.token().z()==observer.z()&&c.fresh(now)&&!c.occupied()&&!refused.contains(c.token()))
            .flatMap(c->c.approaches().stream().map(a->new Choice(c,a)))
            .min(Comparator.comparingDouble((Choice c)->distance(c.approach().position(),observer))
                .thenComparingLong(c->c.candidate().token().generation()).thenComparing(c->c.approach().side())).orElse(null);
    }
    private static double distance(Position a,Position b){double dx=a.x()-b.x(),dy=a.y()-b.y();return dx*dx+dy*dy;}
}
