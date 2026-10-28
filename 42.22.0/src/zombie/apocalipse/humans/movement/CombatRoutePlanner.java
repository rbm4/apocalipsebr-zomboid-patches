package zombie.apocalipse.humans.movement;

import java.util.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded least-effort route to a clear attack position, never to an occupied target center. */
public final class CombatRoutePlanner {
    public interface Grid extends MovementGrid {
        boolean clearAttack(Tile from);
        default double placementScore(Tile from){return 0;}
    }
    private record Node(Tile tile,Node parent,int depth,long cost,long order) {
        Node(Tile tile,Node parent,int depth){this(tile,parent,depth,depth,0);}
    }
    private CombatRoutePlanner() { }
    public static List<Tile> plan(Grid grid,Tile start,float targetX,float targetY,float reach){
        return plan(grid,start,targetX,targetY,0,reach);
    }
    public static List<Tile> plan(Grid grid,Tile start,float targetX,float targetY,float minimum,float reach){
        if(!grid.available()||!grid.walkable(start)||!Float.isFinite(reach)||reach<=0)return List.of();
        if(!Float.isFinite(minimum)||minimum<0||minimum>reach)return List.of();
        var visited=new HashMap<Tile,Node>();
        var queue=new PriorityQueue<Node>(Comparator.comparingLong(Node::cost).thenComparingLong(Node::order));
        long order=0;var root=new Node(start,null,0,0,order++);
        queue.add(root);visited.put(start,root);
        int lineChecks=0;
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        int expanded=0;
        while(!queue.isEmpty()&&expanded++<256){
            var node=queue.remove();if(visited.get(node.tile())!=node)continue;
            double dx=node.tile().x()+.5-targetX,dy=node.tile().y()+.5-targetY;
            if(dx*dx+dy*dy>=minimum*minimum&&dx*dx+dy*dy<=reach*reach&&lineChecks++<16&&grid.clearAttack(node.tile())){
                var route=new ArrayList<Tile>();for(var n=node;n!=null;n=n.parent())route.add(n.tile());
                Collections.reverse(route);return List.copyOf(route);
            }
            if(node.depth()>=32)continue;
            for(var d:directions){
                var next=new Tile(node.tile().x()+d[0],node.tile().y()+d[1]);
                if(!visited.containsKey(next)&&visited.size()>=256||!grid.walkable(next))continue;
                int effort=grid.traversalCost(node.tile(),next);if(effort<=0)continue;
                long cost=node.cost()+effort;var previous=visited.get(next);
                if(previous!=null&&previous.cost()<=cost)continue;
                var child=new Node(next,node,node.depth()+1,cost,order++);visited.put(next,child);queue.add(child);
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
        double initialPlacement=grid.placementScore(start),bestScore=initial+initialPlacement;
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        while(!queue.isEmpty()){
            var node=queue.remove();
            double distance=Math.hypot(node.tile().x()+.5-targetX,node.tile().y()+.5-targetY);
            double score=distance+grid.placementScore(node.tile())-.1*node.depth();
            if(node.depth()>0&&(distance>initial+.2||grid.placementScore(node.tile())>initialPlacement+.5)&&score>bestScore){best=node;bestScore=score;}
            if(node.depth()>=steps)continue;
            for(var d:directions){
                var next=new Tile(node.tile().x()+d[0],node.tile().y()+d[1]);
                if(!seen.contains(next)&&grid.walkable(next)&&grid.traversalCost(node.tile(),next)==1){
                    // Do not briefly close with the selected zombie to reach a better endpoint.
                    if(Math.hypot(next.x()+.5-targetX,next.y()+.5-targetY)<initial&&grid.placementScore(next)<=initialPlacement)continue;
                    seen.add(next);queue.add(new Node(next,node,node.depth()+1));
                }
            }
        }
        if(best==null)return List.of();
        var route=new ArrayList<Tile>();for(var node=best;node!=null;node=node.parent())route.add(node.tile());
        Collections.reverse(route);return List.copyOf(route);
    }
}
