package zombie.apocalipse.humans.movement;

import java.util.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded same-floor A* navigation to an objective. No objective selection or native movement. */
public final class DestinationRoutePlanner {
    private DestinationRoutePlanner() { }
    private record Node(Tile tile, Node parent, int depth, long cost, long order) { }
    public static List<Tile> plan(MovementGrid grid, Tile start, Tile destination) {
        if (!grid.available() || !grid.walkable(start) || !grid.walkable(destination)) return List.of();
        var best = new HashMap<Tile, Node>();
        var queue = new PriorityQueue<Node>(Comparator.comparingLong((Node n) -> n.cost()
            + Math.abs((long)n.tile().x()-destination.x()) + Math.abs((long)n.tile().y()-destination.y()))
            .thenComparing(Comparator.comparingLong(Node::cost).reversed()).thenComparingLong(Node::order));
        long order = 0;
        var root = new Node(start, null, 0, 0, order++); best.put(start, root); queue.add(root);
        int[][] directions = {{1,0},{0,1},{-1,0},{0,-1}};
        for (int expanded = 0; !queue.isEmpty() && expanded < 256; expanded++) {
            var node = queue.remove(); if (best.get(node.tile()) != node) continue;
            if (node.tile().equals(destination)) {
                var route = new ArrayList<Tile>();
                for (var step = node; step != null; step = step.parent()) route.add(step.tile());
                Collections.reverse(route); return List.copyOf(route);
            }
            if (node.depth() >= 64) continue;
            for (var direction : directions) {
                var next = new Tile(node.tile().x()+direction[0], node.tile().y()+direction[1]);
                if (!best.containsKey(next) && best.size() >= 256 || !grid.walkable(next)) continue;
                int cost = grid.traversalCost(node.tile(), next); if (cost <= 0) continue;
                long total = node.cost()+cost; var previous = best.get(next);
                if (previous != null && previous.cost() <= total) continue;
                var child = new Node(next, node, node.depth()+1, total, order++); best.put(next, child); queue.add(child);
            }
        }
        return List.of();
    }
}
