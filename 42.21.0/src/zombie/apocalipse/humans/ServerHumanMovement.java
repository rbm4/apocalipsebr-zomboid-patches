package zombie.apocalipse.humans;

import java.util.List;
import java.util.Objects;
import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.apocalipse.humans.movement.MovementGrid;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.PolygonalMap2;

/** Per-actor movement engine. Executes supplied routes without choosing a destination. */
final class ServerHumanMovement implements MovementGrid {
    static final float WALK_SPEED = 2.0F;
    // One second (two tiles) covers the 500 ms heartbeat plus jitter on straight paths.
    static final double PREDICTION_SECONDS = 1.0;
    static final int MAX_WAYPOINTS = 256;
    enum Gait { IDLE, WALK }
    /** Gait determines native animation; speed is physical tiles per second. */
    record Snapshot(Gait gait, float speed, int predictionUnits) {
        boolean moving() { return gait != Gait.IDLE; }
    }

    private final ServerHuman human;
    private MovementGrid policy;
    private boolean moving;
    private List<Tile> route = List.of();
    private int waypoint;
    private long lastStep;

    ServerHumanMovement(ServerHuman human) { this.human = human; }

    @Override
    public boolean available() { return walkable(new Tile(human.player.getXi(), human.player.getYi())); }

    /** Physical traversal only; callers can supply any bounded cardinal route. */
    boolean start(List<Tile> route, long now) { return start(route, this, now); }

    /** A route policy can restrict physical traversal, never bypass its checks. */
    boolean start(List<Tile> requested, MovementGrid policy, long now) {
        stop();
        Objects.requireNonNull(requested);
        Objects.requireNonNull(policy);
        if (requested.isEmpty() || requested.size() > MAX_WAYPOINTS) return false;
        List<Tile> copy = List.copyOf(requested);
        Tile previous = new Tile(human.player.getXi(), human.player.getYi());
        for (int i = 0; i < copy.size(); i++) {
            Tile tile = copy.get(i);
            long distance = Math.abs((long)tile.x() - previous.x()) + Math.abs((long)tile.y() - previous.y());
            if (distance != 1 && !(i == 0 && distance == 0)) return false;
            previous = tile;
        }
        if (!available() || !policy.available()) return false;
        this.route = copy;
        this.policy = policy;
        lastStep = now;
        return true;
    }

    boolean moving() { return moving; }

    /** Resolved gait/speed for native animation flags and prediction. */
    Snapshot snapshot() {
        return new Snapshot(moving ? Gait.WALK : Gait.IDLE, moving ? WALK_SPEED : 0, predictionDistanceUnits());
    }

    HumanActuator.MotionStatus update(long now) {
        if (route.isEmpty()) return HumanActuator.MotionStatus.FAILED;
        double elapsed = Math.max(0, Math.min(0.15, (now - lastStep) / 1_000_000_000.0));
        lastStep = Math.max(lastStep, now);
        if (!available() || !policy.available()) return fail();
        double remaining = WALK_SPEED * elapsed;
        // Consume the full distance budget, including across tile centers. Previously
        // each reached waypoint cost an extra update and discarded leftover time.
        for (int steps = 0; steps <= MAX_WAYPOINTS; steps++) {
            if (waypoint >= route.size()) {
                stop();
                return HumanActuator.MotionStatus.SUCCEEDED;
            }
            Tile target = route.get(waypoint);
            float tx = target.x() + 0.5F, ty = target.y() + 0.5F;
            float x = human.player.getX(), y = human.player.getY();
            double dx = tx - x, dy = ty - y, distance = Math.hypot(dx, dy);
            if (distance < 0.001) { waypoint++; continue; }
            if (remaining <= 0) return HumanActuator.MotionStatus.RUNNING;
            double step = Math.min(distance, remaining);
            float nx = step == distance ? tx : (float)(x + dx / distance * step);
            float ny = step == distance ? ty : (float)(y + dy / distance * step);
            IsoGridSquare destination = square((int)Math.floor(nx), (int)Math.floor(ny));
            if (!allowed(target) || destination == null || !destination.isFree(true)
                || !allowed(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))) return fail();
            Tile current = new Tile((int)Math.floor(x), (int)Math.floor(y));
            Tile next = new Tile((int)Math.floor(nx), (int)Math.floor(ny));
            if ((!current.equals(next) && !edgeAllowed(current, next)) || blocked(x, y, nx, ny)) return fail();
            human.player.setX(nx);
            human.player.setY(ny);
            human.player.realx = nx;
            human.player.realy = ny;
            human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy, dx)));
            moving = true;
            remaining -= step;
            if (step == distance) waypoint++;
        }
        return fail();
    }

    /** Native prediction distance is quantized in eighths of a tile. */
    int predictionDistanceUnits() {
        if (!moving || waypoint >= route.size()) return 0;
        Tile target = route.get(waypoint);
        float x = human.player.getX(), y = human.player.getY();
        double dx = target.x() + 0.5 - x, dy = target.y() + 0.5 - y;
        double distance = Math.hypot(dx, dy);
        double angle = human.player.getDirectionAngleRadians();
        // Never extrapolate the old heading around an upcoming corner.
        if (distance < 0.001 || (dx * Math.cos(angle) + dy * Math.sin(angle)) / distance < 0.999) return 0;
        if (!allowed(target)) return 0;
        double headingX = dx / distance, headingY = dy / distance;
        // Tile centers on a straight run are not stopping points. Look ahead only
        // through connected, collinear route edges; never predict through a turn.
        for (int i = waypoint + 1; i < route.size() && distance < WALK_SPEED * PREDICTION_SECONDS + 0.15; i++) {
            Tile next = route.get(i);
            int edgeX = next.x() - target.x(), edgeY = next.y() - target.y();
            if (edgeX * headingX + edgeY * headingY < 0.999 || !edgeAllowed(target, next)) break;
            distance += 1.0;
            target = next;
        }
        // Leave headroom for the native client's final frame of extrapolation.
        int units = Math.min(Byte.MAX_VALUE, (int)Math.floor(Math.min(distance - 0.15, WALK_SPEED * PREDICTION_SECONDS) * 8));
        if (units <= 0) return 0;
        double projected = units / 8.0;
        float nx = (float)(x + headingX * projected), ny = (float)(y + headingY * projected);
        IsoGridSquare destination = square((int)Math.floor(nx), (int)Math.floor(ny));
        if (destination == null || !destination.isFree(true)
            || !allowed(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))
            || blocked(x, y, nx, ny)) return 0;
        return units;
    }

    void stop() {
        route = List.of();
        policy = null;
        waypoint = 0;
        moving = false;
        // Greeting recovery restores this last stopping point, not the spawn tile.
        human.postX = human.player.getX();
        human.postY = human.player.getY();
    }

    private HumanActuator.MotionStatus fail() {
        stop();
        return HumanActuator.MotionStatus.FAILED;
    }

    @Override
    public boolean walkable(Tile tile) {
        IsoGridSquare square = square(tile.x(), tile.y());
        return square != null && square.isFree(false) && square.TreatAsSolidFloor() && !square.HasStairs();
    }

    @Override
    public boolean connected(Tile from, Tile to) {
        if (Math.abs((long)from.x() - to.x()) + Math.abs((long)from.y() - to.y()) != 1) return false;
        IsoGridSquare square = square(from.x(), from.y());
        return square != null && walkable(from) && walkable(to)
            && !square.testCollideAdjacentAdvanced(to.x() - from.x(), to.y() - from.y(), 0, false)
            && !blocked(from.x() + 0.5F, from.y() + 0.5F, to.x() + 0.5F, to.y() + 0.5F);
    }

    private boolean allowed(Tile tile) {
        return walkable(tile) && (policy == this || policy.walkable(tile));
    }

    private boolean edgeAllowed(Tile from, Tile to) {
        return connected(from, to) && (policy == this || policy.connected(from, to));
    }

    private boolean blocked(float x, float y, float nx, float ny) {
        return PolygonalMap2.instance.lineClearCollide(x, y, nx, ny, human.definition.z(), null, false, true);
    }

    IsoGridSquare square(int x, int y) {
        return human.player.getCell().getGridSquare(x, y, human.definition.z());
    }
}
