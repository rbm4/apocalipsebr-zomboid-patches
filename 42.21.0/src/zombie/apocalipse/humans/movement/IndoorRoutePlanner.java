package zombie.apocalipse.humans.movement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/** Bounded cardinal-grid search. The grid owns building/floor/collision policy. */
public final class IndoorRoutePlanner {
    public static final int MAX_VISITED = 256;
    public static final int MAX_STEPS = 12;
    public record Tile(int x, int y) { }
    public interface Grid {
        boolean walkable(Tile tile);
        boolean connected(Tile from, Tile to);
    }
    private record Node(Tile tile, Node parent, int depth) { }
    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1}};

    public static List<Tile> plan(Grid grid, Tile start, long seed) {
        if (!grid.walkable(start)) return List.of();
        var seen = new LinkedHashMap<Tile, Node>();
        var queue = new ArrayDeque<Node>();
        Node root = new Node(start, null, 0);
        seen.put(start, root);
        queue.add(root);
        while (!queue.isEmpty() && seen.size() < MAX_VISITED) {
            Node current = queue.remove();
            if (current.depth >= MAX_STEPS) continue;
            for (int[] direction : DIRECTIONS) {
                Tile next = new Tile(current.tile.x + direction[0], current.tile.y + direction[1]);
                if (seen.containsKey(next) || !grid.walkable(next) || !grid.connected(current.tile, next)) continue;
                Node node = new Node(next, current, current.depth + 1);
                seen.put(next, node);
                queue.add(node);
                if (seen.size() >= MAX_VISITED) break;
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
