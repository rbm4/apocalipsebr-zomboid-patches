package zombie.apocalipse.humans.movement;

import java.util.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded shortest indoor route to a clear attack position, never to an occupied target center. */
public final class CombatRoutePlanner {
    public interface Grid extends MovementGrid {
        boolean clearAttack(Tile from);
        default double placementScore(Tile from){return 0;}
    }
    private record Node(Tile tile,Node parent,int depth) { }
    private CombatRoutePlanner() { }
    public static List<Tile> plan(Grid grid,Tile start,float targetX,float targetY,float reach){
        return plan(grid,start,targetX,targetY,0,reach);
    }
    public static List<Tile> plan(Grid grid,Tile start,float targetX,float targetY,float minimum,float reach){
        if(!grid.available()||!grid.walkable(start)||!Float.isFinite(reach)||reach<=0)return List.of();
        if(!Float.isFinite(minimum)||minimum<0||minimum>reach)return List.of();
        var visited=new HashSet<Tile>();var queue=new ArrayDeque<Node>();
        queue.add(new Node(start,null,0));visited.add(start);
        int lineChecks=0;
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        while(!queue.isEmpty()){
            var node=queue.remove();double dx=node.tile().x()+.5-targetX,dy=node.tile().y()+.5-targetY;
            if(dx*dx+dy*dy>=minimum*minimum&&dx*dx+dy*dy<=reach*reach&&lineChecks++<16&&grid.clearAttack(node.tile())){
                var route=new ArrayList<Tile>();for(var n=node;n!=null;n=n.parent())route.add(n.tile());
                Collections.reverse(route);return List.copyOf(route);
            }
            if(node.depth()>=32)continue;
            for(var d:directions){
                var next=new Tile(node.tile().x()+d[0],node.tile().y()+d[1]);
                if(visited.size()>=256)break;
                if(!visited.contains(next)&&grid.walkable(next)&&grid.connected(node.tile(),next)){
                    visited.add(next);queue.add(new Node(next,node,node.depth()+1));
                }
            }
        }
        return List.of();
    }
    /** A short escape leg, not an unlimited path to an ideal firing position. */
    public static List<Tile> withdraw(Grid grid,Tile start,float targetX,float targetY,int steps){
        if(!grid.available()||!grid.walkable(start))return List.of();
        steps=Math.clamp(steps,1,2);
        var queue=new ArrayDeque<Node>();var seen=new HashSet<Tile>();
        queue.add(new Node(start,null,0));seen.add(start);
        Node best=null;
        double initial=Math.hypot(start.x()+.5-targetX,start.y()+.5-targetY);
        double bestScore=initial+grid.placementScore(start);
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        while(!queue.isEmpty()){
            var node=queue.remove();
            double distance=Math.hypot(node.tile().x()+.5-targetX,node.tile().y()+.5-targetY);
            double score=distance+grid.placementScore(node.tile())-.1*node.depth();
            if(node.depth()>0&&distance>initial+.2&&score>bestScore){best=node;bestScore=score;}
            if(node.depth()>=steps)continue;
            for(var d:directions){
                var next=new Tile(node.tile().x()+d[0],node.tile().y()+d[1]);
                if(!seen.contains(next)&&grid.walkable(next)&&grid.connected(node.tile(),next)){
                    // Do not briefly close with the selected zombie to reach a better endpoint.
                    if(Math.hypot(next.x()+.5-targetX,next.y()+.5-targetY)<initial)continue;
                    seen.add(next);queue.add(new Node(next,node,node.depth()+1));
                }
            }
        }
        if(best==null)return List.of();
        var route=new ArrayList<Tile>();for(var node=best;node!=null;node=node.parent())route.add(node.tile());
        Collections.reverse(route);return List.copyOf(route);
    }
}
