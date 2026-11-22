package zombie.apocalipse.humans.survival;

import java.util.*;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded same-floor escape search. Scores the whole crowd and rejects routes through its contact envelope. */
public final class RetreatPlanner {
    private RetreatPlanner(){ }
    public record Threat(double x,double y){
        public Threat {if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Invalid threat position");}
    }
    private record Node(Tile tile,Node parent,int depth,long cost,double clearance,double score,long order){ }
    private static double clearance(Tile tile,List<Threat> threats){
        double result=Double.POSITIVE_INFINITY;
        for(var threat:threats)result=Math.min(result,Math.hypot(tile.x()+.5-threat.x(),tile.y()+.5-threat.y()));
        return result;
    }
    public static List<Tile> plan(MovementGrid grid,Tile start,List<Threat> supplied,int distance){
        return plan(grid,start,supplied,distance,false);
    }
    public static List<Tile> plan(MovementGrid grid,Tile start,List<Threat> supplied,int distance,boolean elevated){
        if(distance<2||distance>24||supplied.isEmpty()||supplied.size()>48||!grid.available()||!grid.walkable(start))return List.of();
        var free=new HashMap<Tile,Boolean>();
        java.util.function.Predicate<Tile> walkable=t->free.computeIfAbsent(t,grid::walkable);
        var threats=List.copyOf(supplied);
        double initial=clearance(start,threats),floor=Math.min(elevated?.65:1.25,initial);
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score).reversed().thenComparingLong(Node::order));
        var seen=new HashSet<Tile>();long order=0;
        var root=new Node(start,null,0,0,initial,initial,order++);queue.add(root);seen.add(start);Node best=null;
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        for(int work=0;!queue.isEmpty()&&work<256;work++){
            var node=queue.remove();
            if(node.clearance()>=initial+2){
                int exits=0;
                for(var direction:directions){var next=new Tile(node.tile().x()+direction[0],node.tile().y()+direction[1]);
                    if(walkable.test(next)&&grid.traversalCost(node.tile(),next)>0)exits++;}
                // Avoid replacing one close contact with a pocket against furniture or walls.
                double value=node.score()+(exits>=2?.35*exits:-3);
                if(best==null||value>best.score())best=new Node(node.tile(),node.parent(),node.depth(),node.cost(),node.clearance(),value,node.order());
            }
            if(node.depth()>=distance*2)continue;
            for(var direction:directions){
                var tile=new Tile(node.tile().x()+direction[0],node.tile().y()+direction[1]);
                if(Math.abs((long)tile.x()-start.x())+Math.abs((long)tile.y()-start.y())>distance||seen.contains(tile)||seen.size()>=256||!walkable.test(tile))continue;
                double gap=clearance(tile,threats);if(gap+.0001<floor)continue;
                int edge=grid.traversalCost(node.tile(),tile);if(edge<=0)continue;
                long cost=node.cost()+edge;int depth=node.depth()+1;
                double score=Math.min(24,gap)-depth*.10-cost*.04;
                seen.add(tile);queue.add(new Node(tile,node,depth,cost,gap,score,order++));
            }
        }
        if(best==null)return List.of();
        var route=new ArrayList<Tile>();for(var node=best;node!=null;node=node.parent())route.add(node.tile());
        Collections.reverse(route);return List.copyOf(route);
    }
}
