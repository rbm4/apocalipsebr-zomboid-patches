package zombie.apocalipse.humans.movement;

import java.util.Objects;

/** Local objective scope, including a bounded approach corridor from the captured origin. */
public final class VicinityGrid implements MovementGrid {
    private final MovementGrid world;
    private final int left,right,top,bottom;
    private final boolean bounded;
    public VicinityGrid(MovementGrid world,Tile start,Tile center,int radius){
        this.world=Objects.requireNonNull(world);
        if(radius<1||radius>8)throw new IllegalArgumentException("Invalid vicinity radius");
        left=Math.min(start.x(),center.x())-radius;right=Math.max(start.x(),center.x())+radius;
        top=Math.min(start.y(),center.y())-radius;bottom=Math.max(start.y(),center.y())+radius;
        bounded=(long)right-left<=64&&(long)bottom-top<=64;
    }
    public boolean available(){return bounded&&world.available();}
    public boolean walkable(Tile tile){return bounded&&tile.x()>=left&&tile.x()<=right&&tile.y()>=top&&tile.y()<=bottom&&world.walkable(tile);}
    public boolean connected(Tile from,Tile to){return traversalCost(from,to)>0;}
    public int traversalCost(Tile from,Tile to){return walkable(from)&&walkable(to)?world.traversalCost(from,to):0;}
}
