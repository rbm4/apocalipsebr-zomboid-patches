package zombie.apocalipse.humans;

import java.util.*;
import zombie.apocalipse.humans.brain.HumanActuator.MotionStatus;
import zombie.apocalipse.humans.movement.*;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.apocalipse.humans.walking.*;

/** Navigation adapter and per-request ownership; pattern selection is supplied by the profile or objective. */
final class ServerHumanWalking implements WalkingPattern.World {
    private final ServerHuman human;
    private final Map<String, WalkingPattern> patterns = new HashMap<>();
    private WalkingPattern active;
    private long sequence;
    ServerHumanWalking(ServerHuman human) {
        this.human = human;
        for (var spec : human.profile.walkingPatterns()) patterns.put(spec.slot(), ServerHumanProfiles.walkingPatterns().create(spec));
    }
    private WalkingPattern.Context context() {
        return new WalkingPattern.Context(this, new Tile(human.player.getXi(), human.player.getYi()),
            new Tile((int)human.definition.x(), (int)human.definition.y()), human.player.getZi(),
            ((long)human.player.getOnlineID()<<32) ^ sequence);
    }
    MovementGrid scope(String slot) {
        var pattern = patterns.get(slot); return pattern == null ? null : pattern.scope(context());
    }
    boolean permitted(Tile tile) {
        var grid=scope("background");return grid==null?walkable(tile):grid.walkable(tile);
    }
    boolean start(MovementRequest request, long now) {
        stop();
        if (!available()) return false;
        sequence++;
        var context = context();
        MovementGrid scope; List<Tile> route;
        if (request instanceof MovementRequest.Pattern pattern) {
            active = patterns.get(pattern.slot()); if (active == null) return false;
            scope = active.scope(context); route = active.plan(context);
        } else {
            var objective = (MovementRequest.To)request;
            var destination = objective.destination();
            if (destination.z() != context.z()) return false;
            // An objective may select its destination, but cannot bypass the profile's assigned movement area.
            var assigned=scope(objective.slot());
            if(assigned==null&&!objective.slot().equals("background"))return false;
            scope=assigned==null?this:assigned;
            route = DestinationRoutePlanner.plan(scope, context.current(), new Tile(destination.x(), destination.y()));
        }
        if (!human.movement.start(route, scope, now)) { finish(false); return false; }
        return true;
    }
    MotionStatus update(long now) {
        var result = human.movement.update(now);
        if (result != MotionStatus.RUNNING) finish(result == MotionStatus.SUCCEEDED);
        return result;
    }
    private void finish(boolean success) { var previous = active; active = null; if (previous != null) previous.completed(success); }
    void stop() { try { human.movement.stop(); } finally { finish(false); } }
    public boolean available() { return human.movement.available(); }
    public boolean walkable(Tile tile) { return human.movement.walkable(tile); }
    public boolean connected(Tile from, Tile to) { return human.movement.connected(from, to); }
    public int traversalCost(Tile from, Tile to) { return human.movement.traversalCost(from, to); }
    public boolean sameBuilding(Tile first, Tile second) {
        var a = human.movement.square(first.x(), first.y()); var b = human.movement.square(second.x(), second.y());
        return a != null && b != null && a.getBuildingDef() != null && a.getBuildingDef() == b.getBuildingDef();
    }
}
