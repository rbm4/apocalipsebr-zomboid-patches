package zombie.apocalipse.humans.survival;

import java.util.LinkedHashMap;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Per-actor short-lived failed-edge memory. Never turns a temporary body blockage into a permanent wall. */
public final class RouteRefusals {
    private record Edge(Tile from,Tile to){ }
    private final LinkedHashMap<Edge,Long> edges=new LinkedHashMap<>();
    public void refuse(Tile from,Tile to,long now,boolean temporary){
        expire(now);var edge=new Edge(from,to);
        edges.remove(edge);if(edges.size()>=32)edges.remove(edges.keySet().iterator().next());
        edges.put(edge,now+(temporary?3_000_000_000L:10_000_000_000L));
    }
    private void expire(long now){edges.values().removeIf(until->now>=until);}
    public boolean blocked(Tile from,Tile to,long now){expire(now);return edges.containsKey(new Edge(from,to));}
}
