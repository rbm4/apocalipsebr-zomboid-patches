package zombie.apocalipse.humans.traversal;

import java.util.LinkedHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import zombie.apocalipse.humans.movement.MovementGrid.Tile;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.network.PacketTypes;
import zombie.pathfind.PolygonalMap2;

/** Native geometry/interaction adapter for a detached server IsoPlayer. All calls stay on the simulation thread. */
public final class HumanTraversal implements TraversalExecution.Port {
    private final IsoPlayer player;
    private final int z;
    private final TraversalWorld world;
    private final BiPredicate<Tile, Tile> policy;
    private final Consumer<Boolean> presentation;
    private final Runnable positionChanged;
    private final TraversalExecution execution;
    private final LinkedHashMap<CrossingReservations.Key, Long> refused = new LinkedHashMap<>();
    private TraversalEdge displayed;
    private TraversalEdge pending;
    private boolean displayedLanded;
    private long clock = Long.MIN_VALUE;

    public HumanTraversal(IsoPlayer player, int z, TraversalWorld world,
                          BiPredicate<Tile, Tile> policy, Consumer<Boolean> presentation, Runnable positionChanged) {
        this.player = player; this.z = z; this.world = world; this.policy = policy; this.presentation = presentation;
        this.positionChanged = positionChanged;
        execution = new TraversalExecution(player, world.reservations, this);
        world.add(player);
    }
    public boolean active() { return execution.active(); }
    public void clock(long now) { clock = Math.max(clock, now); }
    public String reason() { return execution.reason(); }
    public void start(TraversalEdge edge, long now) { clock = Math.max(clock, now); execution.start(edge, clock); pending = edge; }
    public TraversalExecution.Result update(long now) {
        clock = Math.max(clock, now);
        TraversalEdge attempted = pending;
        boolean wasLanded = displayedLanded;
        var result = execution.update(clock);
        if (result == TraversalExecution.Result.FAILED && attempted != null
            && !reason().equals("landing_occupied") && !reason().equals("crossing_busy")) {
            if (refused.size() >= 64) refused.remove(refused.keySet().iterator().next());
            refused.put(CrossingReservations.Key.of(attempted), clock + 15_000_000_000L);
        }
        if (result != TraversalExecution.Result.RUNNING) pending = null;
        // Notify only after the execution recorded commitment; a transport failure cannot undo a landing.
        if (!wasLanded && displayed != null && displayedLanded) positionChanged.run();
        return result;
    }
    public void cancel() { try { execution.cancel(); } finally { pending = null; } }
    public void close() { try { cancel(); } finally { world.remove(player); refused.clear(); } }
    public void send(UdpConnection viewer, boolean entering) {
        if (displayed != null) new TraversalStatePacket(player, displayed, entering, displayedLanded)
            .sendToClient(PacketTypes.PacketType.State, viewer);
    }
    /** A viewer joining after commitment must not be pulled back to replay an old crossing. */
    public void announce(UdpConnection viewer) { if (displayed != null && !execution.committed()) send(viewer, true); }

    public IsoGridSquare square(Tile tile) { return player.getCell().getGridSquare(tile.x(), tile.y(), z); }
    public boolean walkable(Tile tile) {
        IsoGridSquare square = square(tile);
        return square != null && square.isFree(false) && square.TreatAsSolidFloor() && !square.HasStairs()
            && !square.has(IsoFlagType.water);
    }
    public TraversalEdge inspect(Tile from, Tile to) {
        TraversalEdge blocked = new TraversalEdge(from, to, z, TraversalEdge.Kind.BLOCKED, null);
        if (Math.abs((long)from.x() - to.x()) + Math.abs((long)from.y() - to.y()) != 1
            || !walkable(from) || !walkable(to)) return blocked;
        if (refused.getOrDefault(CrossingReservations.Key.of(blocked), Long.MIN_VALUE) > clock) return blocked;
        IsoGridSquare a = square(from), b = square(to);
        IsoObject door = a.getDoorTo(b);
        if (door instanceof IsoDoor d && !d.IsOpen()) {
            return new TraversalEdge(from, to, z, doorOpenable(d, a) ? TraversalEdge.Kind.DOOR : TraversalEdge.Kind.BLOCKED, d);
        }
        if (door instanceof IsoThumpable d && d.isDoor() && !d.IsOpen()) {
            return new TraversalEdge(from, to, z, doorOpenable(d, a) ? TraversalEdge.Kind.DOOR : TraversalEdge.Kind.BLOCKED, d);
        }
        IsoWindow window = a.getWindowTo(b);
        if (window != null) return new TraversalEdge(from, to, z,
            windowPassable(window) ? TraversalEdge.Kind.WINDOW : TraversalEdge.Kind.BLOCKED, window);
        IsoWindowFrame frame = a.getWindowFrameTo(b);
        if (frame != null) return new TraversalEdge(from, to, z,
            frame.canClimbThrough(null) ? TraversalEdge.Kind.WINDOW : TraversalEdge.Kind.BLOCKED, frame);
        IsoThumpable thump = a.getWindowThumpableTo(b);
        if (thump != null) return new TraversalEdge(from, to, z,
            thump.canClimbThrough(null) ? TraversalEdge.Kind.WINDOW : TraversalEdge.Kind.BLOCKED, thump);
        IsoObject fence = a.getHoppableTo(b);
        if (fence != null) {
            boolean railing = fence.getSprite() != null && fence.getSprite().getName() != null
                && fence.getSprite().getName().contains("fixtures_railings");
            var edge = new TraversalEdge(from, to, z, TraversalEdge.Kind.FENCE, fence);
            return !fence.isTallHoppable() && !railing && a.isPlayerAbleToHopWallTo(TraversalStatePacket.direction(edge), b) ? edge : blocked;
        }
        return new TraversalEdge(from, to, z, walkingEdge(from, to) ? TraversalEdge.Kind.WALK : TraversalEdge.Kind.BLOCKED, null);
    }
    public boolean walkingEdge(Tile from, Tile to) {
        IsoGridSquare a = square(from);
        return a != null && walkable(from) && walkable(to)
            && !a.testCollideAdjacentAdvanced(to.x() - from.x(), to.y() - from.y(), 0, false)
            && !PolygonalMap2.instance.lineClearCollide(from.x()+.5F, from.y()+.5F, to.x()+.5F, to.y()+.5F, z, null, false, true);
    }
    private boolean windowPassable(IsoWindow window) {
        if (window.isInvincible() || window.isBarricaded()) return false;
        if (window.canClimbThrough(null)) return !window.isSmashed() || window.isGlassRemoved();
        return !window.IsOpen() && !window.isSmashed() && !window.isLocked() && !window.isPermaLocked();
    }
    /** setCurrent is a reference assignment in 42.21; never adds square membership or pins chunks. */
    private <T> T at(IsoGridSquare source, Supplier<T> operation) {
        IsoGridSquare previous = player.getCurrentSquare();
        player.setCurrent(source);
        try { return operation.get(); } finally { player.setCurrent(previous); }
    }
    private boolean doorOpenable(IsoObject door, IsoGridSquare source) {
        return at(source, () -> door instanceof IsoDoor d ? !d.isBarricaded() && !d.isObstructed() && d.couldBeOpen(player)
            : door instanceof IsoThumpable thump && !thump.isBarricaded() && !thump.isObstructed() && thump.couldBeOpen(player));
    }
    @Override public TraversalEdge inspect(TraversalEdge planned) {
        if (!policy.test(planned.from(), planned.to()))
            return new TraversalEdge(planned.from(), planned.to(), z, TraversalEdge.Kind.BLOCKED, null);
        return inspect(planned.from(), planned.to());
    }
    @Override public TraversalExecution.Landing landing(TraversalEdge edge) {
        IsoGridSquare b = square(edge.to());
        if (!walkable(edge.to()) || !PolygonalMap2.instance.canStandAt(edge.to().x()+.5F, edge.to().y()+.5F, z, null, false, true))
            return TraversalExecution.Landing.BLOCKED;
        if (!b.isFree(true) || world.occupied(player, b)) return TraversalExecution.Landing.OCCUPIED;
        return TraversalExecution.Landing.CLEAR;
    }
    @Override public boolean prepare(TraversalEdge edge) {
        Object object = edge.obstacle();
        if (edge.kind() == TraversalEdge.Kind.DOOR) return at(square(edge.from()), () -> {
            if (!doorOpenable((IsoObject)object, square(edge.from()))) return false;
            if (object instanceof IsoDoor d) { if (!d.IsOpen()) d.ToggleDoor(player); return d.IsOpen(); }
            IsoThumpable d = (IsoThumpable)object;
            if (!d.IsOpen()) d.ToggleDoor(player);
            return d.IsOpen();
        });
        if (object instanceof IsoWindow w) {
            if (!windowPassable(w)) return false;
            if (!w.canClimbThrough(null)) w.ToggleWindow(player);
            return w.canClimbThrough(player) && (!w.isSmashed() || w.isGlassRemoved());
        }
        if (object instanceof IsoWindowFrame w) return w.canClimbThrough(player);
        if (object instanceof IsoThumpable w && edge.kind() == TraversalEdge.Kind.WINDOW) return w.canClimbThrough(player);
        return square(edge.from()).isPlayerAbleToHopWallTo(TraversalStatePacket.direction(edge), square(edge.to()));
    }
    @Override public void begin(TraversalEdge edge) {
        player.setRunning(false); player.setSprinting(false);
        player.setDirectionAngle((float)Math.toDegrees(Math.atan2(edge.to().y()-edge.from().y(), edge.to().x()-edge.from().x())));
        displayed = edge; displayedLanded = false;
        presentation.accept(true);
    }
    @Override public void land(TraversalEdge edge) {
        player.setX(edge.to().x()+.5F); player.setY(edge.to().y()+.5F);
        player.realx = player.getX(); player.realy = player.getY();
        displayedLanded = true;
    }
    @Override public void end(TraversalEdge edge, boolean landed) {
        displayedLanded = landed;
        try { presentation.accept(false); } finally { displayed = null; }
    }
}
