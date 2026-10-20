package zombie.apocalipse.humans;

import java.util.List;
import zombie.apocalipse.humans.brain.HumanActuator;
import zombie.apocalipse.humans.movement.IndoorRoutePlanner;
import zombie.apocalipse.humans.movement.IndoorRoutePlanner.Tile;
import zombie.iso.BuildingDef;
import zombie.iso.IsoGridSquare;
import zombie.pathfind.PolygonalMap2;

/** Server-owned position integration; no player input, fake connection or client AI. */
final class ServerHumanRoaming implements IndoorRoutePlanner.Grid {
    static final float SPEED = 1.2F;
    // Cover the 500 ms heartbeat plus scheduling/network jitter on straight paths.
    static final double PREDICTION_SECONDS = 0.75;
    private final ServerHuman human;
    private BuildingDef building;
    private List<Tile> route = List.of();
    private int waypoint;
    private long lastStep;
    private long sequence;

    ServerHumanRoaming(ServerHuman human) { this.human = human; }

    boolean available() {
        IsoGridSquare home = square((int)human.definition.x(), (int)human.definition.y());
        if (home == null) return false;
        if (building == null) building = home.getBuildingDef();
        return building != null && home.getBuildingDef() == building
            && walkable(new Tile(human.player.getXi(), human.player.getYi()));
    }

    boolean start(long now) {
        stop();
        if (!available()) return false;
        route = IndoorRoutePlanner.plan(this, new Tile(human.player.getXi(), human.player.getYi()),
            ((long)human.player.getOnlineID() << 32) ^ ++sequence);
        waypoint = 0;
        lastStep = now;
        return !route.isEmpty();
    }

    HumanActuator.MotionStatus update(long now) {
        if (route.isEmpty()) return HumanActuator.MotionStatus.FAILED;
        double elapsed = Math.max(0, Math.min(0.15, (now - lastStep) / 1_000_000_000.0));
        lastStep = now;
        if (!available()) return fail();
        double remaining = SPEED * elapsed;
        // Consume the full distance budget, including across tile centers. Previously
        // each reached waypoint cost an extra update and discarded leftover time.
        for (int steps = 0; steps <= IndoorRoutePlanner.MAX_STEPS + 1; steps++) {
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
            if (!walkable(target) || destination == null || !destination.isFree(true)
                || !walkable(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))) return fail();
            Tile current = new Tile((int)Math.floor(x), (int)Math.floor(y));
            Tile next = new Tile((int)Math.floor(nx), (int)Math.floor(ny));
            if ((!current.equals(next) && !connected(current, next)) || blocked(x, y, nx, ny)) return fail();
            human.player.setX(nx);
            human.player.setY(ny);
            human.player.realx = nx;
            human.player.realy = ny;
            human.player.setDirectionAngle((float)Math.toDegrees(Math.atan2(dy, dx)));
            human.moving = true;
            remaining -= step;
            if (step == distance) waypoint++;
        }
        return fail();
    }

    /** Native prediction distance is quantized in eighths of a tile. */
    int predictionDistanceUnits() {
        if (!human.moving || waypoint >= route.size()) return 0;
        Tile target = route.get(waypoint);
        float x = human.player.getX(), y = human.player.getY();
        double dx = target.x() + 0.5 - x, dy = target.y() + 0.5 - y;
        double distance = Math.hypot(dx, dy);
        double angle = human.player.getDirectionAngleRadians();
        // Never extrapolate the old heading around an upcoming corner.
        if (distance < 0.001 || (dx * Math.cos(angle) + dy * Math.sin(angle)) / distance < 0.999) return 0;
        if (!walkable(target)) return 0;
        double headingX = dx / distance, headingY = dy / distance;
        // Tile centers on a straight run are not stopping points. Look ahead only
        // through connected, collinear route edges; never predict through a turn.
        for (int i = waypoint + 1; i < route.size() && distance < SPEED * PREDICTION_SECONDS + 0.15; i++) {
            Tile next = route.get(i);
            int edgeX = next.x() - target.x(), edgeY = next.y() - target.y();
            if (edgeX * headingX + edgeY * headingY < 0.999 || !connected(target, next)) break;
            distance += 1.0;
            target = next;
        }
        // Leave headroom for the native client's final frame of extrapolation.
        int units = (int)Math.floor(Math.min(distance - 0.15, SPEED * PREDICTION_SECONDS) * 8);
        if (units <= 0) return 0;
        double projected = units / 8.0;
        float nx = (float)(x + headingX * projected), ny = (float)(y + headingY * projected);
        IsoGridSquare destination = square((int)Math.floor(nx), (int)Math.floor(ny));
        if (destination == null || !destination.isFree(true)
            || !walkable(new Tile((int)Math.floor(nx), (int)Math.floor(ny)))
            || blocked(x, y, nx, ny)) return 0;
        return units;
    }

    void stop() {
        route = List.of();
        human.moving = false;
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
        return building != null && square != null && square.getBuildingDef() == building
            && square.isFree(false) && square.TreatAsSolidFloor() && !square.HasStairs();
    }

    @Override
    public boolean connected(Tile from, Tile to) {
        if (Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) != 1) return false;
        IsoGridSquare square = square(from.x(), from.y());
        return square != null && walkable(from) && walkable(to)
            && !square.testCollideAdjacentAdvanced(to.x() - from.x(), to.y() - from.y(), 0, false)
            && !blocked(from.x() + 0.5F, from.y() + 0.5F, to.x() + 0.5F, to.y() + 0.5F);
    }

    private boolean blocked(float x, float y, float nx, float ny) {
        return PolygonalMap2.instance.lineClearCollide(x, y, nx, ny, human.definition.z(), null, false, true);
    }

    private IsoGridSquare square(int x, int y) {
        return human.player.getCell().getGridSquare(x, y, human.definition.z());
    }
}
