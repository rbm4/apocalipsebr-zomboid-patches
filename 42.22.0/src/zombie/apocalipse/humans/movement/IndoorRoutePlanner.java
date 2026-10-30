package zombie.apocalipse.humans.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.PriorityQueue;
import java.util.Comparator;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;

/** Bounded cardinal Dijkstra search. The grid owns building/floor/traversal policy and effort. */
public final class IndoorRoutePlanner {
    public static final int MAX_VISITED = 256;
    public static final int MAX_STEPS = 12;
    public interface Grid extends MovementGrid { }
    private record Node(Tile tile, Node parent, int depth, long cost, long order) { }
    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1}};

    public static List<Tile> plan(Grid grid, Tile start, long seed) {
        if (!grid.walkable(start)) return List.of();
        var seen = new LinkedHashMap<Tile, Node>();
        var queue = new PriorityQueue<Node>(Comparator.comparingLong(Node::cost).thenComparingLong(Node::order));
        long order = 0;
        Node root = new Node(start, null, 0, 0, order++);
        seen.put(start, root);
        queue.add(root);
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < MAX_VISITED) {
            Node current = queue.remove();
            if (seen.get(current.tile) != current) continue;
            if (current.depth >= MAX_STEPS) continue;
            for (int[] direction : DIRECTIONS) {
                Tile next = new Tile(current.tile.x() + direction[0], current.tile.y() + direction[1]);
                if (!seen.containsKey(next) && seen.size() >= MAX_VISITED || !grid.walkable(next)) continue;
                int effort = grid.traversalCost(current.tile, next);
                if (effort <= 0) continue;
                long cost = current.cost + effort;
                Node previous = seen.get(next);
                if (previous != null && previous.cost <= cost) continue;
                Node node = new Node(next, current, current.depth + 1, cost, order++);
                seen.put(next, node);
                queue.add(node);
            }
        }
        var candidates = new ArrayList<Node>();
        for (Node node : seen.values()) if (node.depth >= 2) candidates.add(node);
        if (candidates.isEmpty()) for (Node node : seen.values()) if (node.depth > 0) candidates.add(node);
        if (candidates.isEmpty()) return List.of();
        Node end = candidates.get(new Random(seed).nextInt(candidates.size()));
        var route = new ArrayList<Tile>();
        for (Node node = end; node != null; node = node.parent) route.add(node.tile);
        Collections.reverse(route);
        return List.copyOf(route);
    }

    private IndoorRoutePlanner() { }
}
