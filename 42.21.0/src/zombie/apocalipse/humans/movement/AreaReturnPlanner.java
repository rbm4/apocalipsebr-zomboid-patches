package zombie.apocalipse.humans.movement;

import java.util.*;
import java.util.function.Predicate;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded recovery into a designated domain, across unrestricted physical space.
 * A partial leg is allowed only when it makes progress toward the domain anchor. */
public final class AreaReturnPlanner {
    private AreaReturnPlanner() { }
    private record Node(Tile tile,Node parent,int depth,long cost,long order) { }
    private static long distance(Tile tile,Tile anchor){return Math.abs((long)tile.x()-anchor.x())+Math.abs((long)tile.y()-anchor.y());}
    public static List<Tile> plan(MovementGrid world,Tile start,Predicate<Tile> inside,Tile anchor){
        if(!world.available()||!world.walkable(start)||inside.test(start))return List.of();
        var best=new HashMap<Tile,Node>();
        var queue=new PriorityQueue<Node>(Comparator.comparingLong((Node n)->n.cost()+distance(n.tile(),anchor))
            .thenComparing(Comparator.comparingLong(Node::cost).reversed()).thenComparingLong(Node::order));
        long order=0;var root=new Node(start,null,0,0,order++);best.put(start,root);queue.add(root);
        Node progress=root;
        int[][] directions={{1,0},{0,1},{-1,0},{0,-1}};
        for(int work=0;!queue.isEmpty()&&work<256;work++){
            var node=queue.remove();if(best.get(node.tile())!=node)continue;
            if(inside.test(node.tile()))return route(node);
            if(distance(node.tile(),anchor)<distance(progress.tile(),anchor))progress=node;
            if(node.depth()>=64)continue;
            for(var direction:directions){
                long x=(long)node.tile().x()+direction[0],y=(long)node.tile().y()+direction[1];
                if(x<0||y<0||x>=Integer.MAX_VALUE||y>=Integer.MAX_VALUE)continue;
                var next=new Tile((int)x,(int)y);
                if(!best.containsKey(next)&&best.size()>=256||!world.walkable(next))continue;
                int edge=world.traversalCost(node.tile(),next);if(edge<=0)continue;
                long cost=node.cost()+edge;var old=best.get(next);if(old!=null&&old.cost()<=cost)continue;
                var child=new Node(next,node,node.depth()+1,cost,order++);best.put(next,child);queue.add(child);
            }
        }
        return progress==root?List.of():route(progress);
    }
    private static List<Tile> route(Node node){
        var result=new ArrayList<Tile>();for(var step=node;step!=null;step=step.parent())result.add(step.tile());
        Collections.reverse(result);return List.copyOf(result);
    }
}
