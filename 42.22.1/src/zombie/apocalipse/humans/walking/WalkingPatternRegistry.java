package zombie.apocalipse.humans.walking;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.profiles.HumanProfile;

/** Allowlisted code factories. Each activation gets fresh pattern state, including circuit progression. */
public final class WalkingPatternRegistry {
    private record Factory(Consumer<WalkingPatternSpec> validate, Function<WalkingPatternSpec, WalkingPattern> create) { }
    private final Map<String, Factory> factories = new HashMap<>();
    public void register(String id, Consumer<WalkingPatternSpec> validate, Function<WalkingPatternSpec, WalkingPattern> create) {
        HumanProfile.identifier(id);
        if (factories.putIfAbsent(id, new Factory(Objects.requireNonNull(validate), Objects.requireNonNull(create))) != null)
            throw new IllegalArgumentException("Duplicate walking pattern");
    }
    public void validate(WalkingPatternSpec spec) {
        Factory factory = factories.get(spec.patternId());
        if (factory == null) throw new IllegalArgumentException("Unknown walking pattern: " + spec.patternId());
        factory.validate().accept(spec);
    }
    public WalkingPattern create(WalkingPatternSpec spec) { validate(spec); return Objects.requireNonNull(factories.get(spec.patternId()).create().apply(spec)); }
    public static WalkingPatternRegistry builtins() {
        var registry = new WalkingPatternRegistry();
        Consumer<WalkingPatternSpec> wander = spec -> { if (!spec.points().isEmpty()) throw new IllegalArgumentException("Wandering does not accept waypoints"); };
        registry.register("indoor-wander", wander, spec -> new Wander(spec, true));
        registry.register("area-wander", wander, spec -> new Wander(spec, false));
        registry.register("waypoint-circuit", spec -> {
            if (spec.points().isEmpty() || spec.points().stream().map(WalkingPatternSpec.Point::z).distinct().count() != 1)
                throw new IllegalArgumentException("Circuit requires 1-32 same-floor waypoints");
            if(spec.area()!=null&&spec.points().stream().anyMatch(p->!spec.area().contains(p.x(),p.y())))throw new IllegalArgumentException("Waypoint outside movement area");
        }, Circuit::new);
        return registry;
    }
    private static MovementGrid restrict(WalkingPattern.Context context, Predicate<Tile> permitted) {
        return new MovementGrid() {
            public boolean available() { return context.world().available() && walkable(context.current()); }
            public boolean walkable(Tile tile) { return permitted.test(tile) && context.world().walkable(tile); }
            public boolean connected(Tile from, Tile to) { return walkable(from) && walkable(to) && context.world().connected(from, to); }
            public int traversalCost(Tile from, Tile to) { return walkable(from) && walkable(to) ? context.world().traversalCost(from, to) : 0; }
        };
    }
    private record Wander(WalkingPatternSpec spec, boolean indoors) implements WalkingPattern {
        public MovementGrid scope(Context context) {
            return restrict(context, tile -> (spec.area()==null||spec.area().contains(tile.x(),tile.y())) && (indoors ? context.world().sameBuilding(context.home(), tile)
                : Math.abs((long)tile.x()-context.home().x()) <= spec.radiusTiles() && Math.abs((long)tile.y()-context.home().y()) <= spec.radiusTiles()));
        }
        public List<Tile> plan(Context context) {
            MovementGrid grid = scope(context);
            return IndoorRoutePlanner.plan(new IndoorRoutePlanner.Grid() {
                public boolean available() { return grid.available(); }
                public boolean walkable(Tile tile) { return grid.walkable(tile); }
                public boolean connected(Tile from, Tile to) { return grid.connected(from, to); }
                public int traversalCost(Tile from, Tile to) { return grid.traversalCost(from, to); }
            }, context.current(), context.sequence());
        }
    }
    private static final class Circuit implements WalkingPattern {
        private final WalkingPatternSpec spec;
        private int cursor;
        Circuit(WalkingPatternSpec spec) { this.spec = spec; }
        public MovementGrid scope(Context context) {
            return restrict(context, tile -> (spec.area()==null||spec.area().contains(tile.x(),tile.y()))
                && Math.abs((long)tile.x()-context.current().x()) <= 64 && Math.abs((long)tile.y()-context.current().y()) <= 64);
        }
        public List<Tile> plan(Context context) {
            var destination = spec.points().get(cursor);
            if (destination.z() != context.z()) return List.of();
            return DestinationRoutePlanner.plan(scope(context), context.current(), new Tile(destination.x(), destination.y()));
        }
        public void completed(boolean success) { if (success) cursor = (cursor+1) % spec.points().size(); }
    }
}
