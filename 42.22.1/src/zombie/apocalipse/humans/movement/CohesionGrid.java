package zombie.apocalipse.humans.movement;

import java.util.List;
import zombie.apocalipse.humans.walking.WalkingPatternSpec.Point;

/** Soft route preference toward companions; geometry/occupancy still belongs to the live delegate. */
public final class CohesionGrid implements MovementGrid {
    private final MovementGrid delegate;
    private final List<Point> companions;
    public CohesionGrid(MovementGrid delegate,List<Point> companions){
        this.delegate=java.util.Objects.requireNonNull(delegate);this.companions=List.copyOf(companions);
        if(companions.size()>7)throw new IllegalArgumentException("Too many companions");
    }
    public boolean available(){return delegate.available();}
    public boolean walkable(Tile tile){return delegate.walkable(tile);}
    public boolean connected(Tile from,Tile to){return delegate.connected(from,to);}
    public int traversalCost(Tile from,Tile to){
        int cost=delegate.traversalCost(from,to);if(cost<=0||companions.isEmpty())return cost;
        double x=0,y=0;for(var point:companions){x+=point.x()+.5;y+=point.y()+.5;}
        double distance=Math.hypot(to.x()+.5-x/companions.size(),to.y()+.5-y/companions.size());
        return (int)Math.min(Integer.MAX_VALUE,(long)cost+(int)Math.clamp(Math.ceil((distance-6)/2),0,8));
    }
}
