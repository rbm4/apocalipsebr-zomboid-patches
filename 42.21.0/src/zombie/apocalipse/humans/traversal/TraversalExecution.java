package zombie.apocalipse.humans.traversal;

/** Per-actor authoritative crossing. Native client animation is presentation, never an acknowledgement. */
public final class TraversalExecution {
    public enum Landing { CLEAR, OCCUPIED, BLOCKED }
    public enum Result { RUNNING, SUCCEEDED, FAILED }
    public interface Port {
        TraversalEdge inspect(TraversalEdge planned);
        Landing landing(TraversalEdge edge);
        boolean prepare(TraversalEdge edge);
        void begin(TraversalEdge edge);
        void land(TraversalEdge edge);
        void end(TraversalEdge edge, boolean landed);
    }
    private final Object owner;
    private final CrossingReservations reservations;
    private final Port port;
    private TraversalEdge edge;
    private CrossingReservations.Lease lease;
    private long clock, waitingSince, started;
    private boolean begun, landed;
    private String reason = "idle";

    public TraversalExecution(Object owner, CrossingReservations reservations, Port port) {
        this.owner = owner; this.reservations = reservations; this.port = port;
    }
    public void start(TraversalEdge edge, long now) {
        cancel();
        if (!edge.usable() || edge.kind() == TraversalEdge.Kind.WALK) throw new IllegalArgumentException("action edge required");
        this.edge = edge; clock = waitingSince = now; reason = "waiting";
    }
    public boolean active() { return edge != null; }
    public boolean committed() { return landed; }
    public String reason() { return reason; }

    public Result update(long now) {
        if (edge == null) return Result.FAILED;
        clock = Math.max(clock, now);
        try {
            if (lease != null && !reservations.owns(lease, clock)) return finish(false, "lease_expired");
            if (!landed) {
                TraversalEdge current = port.inspect(edge);
                // A door opened or an obstacle removed while we waited: ordinary movement must recheck collision.
                if (current.kind() == TraversalEdge.Kind.WALK) return finish(true, "passage_cleared");
                if (!edge.sameOpening(current)) return finish(false, "opening_changed");
                Landing landing = port.landing(edge);
                if (landing == Landing.BLOCKED) return finish(false, "landing_blocked");
                if (landing == Landing.OCCUPIED) {
                    if (begun || clock - waitingSince >= 2_000_000_000L) return finish(false, "landing_occupied");
                    return Result.RUNNING;
                }
            }
            if (!begun) {
                if (lease == null) lease = reservations.acquire(owner, edge, clock);
                if (lease == null) {
                    if (clock - waitingSince >= 2_000_000_000L) return finish(false, "crossing_busy");
                    return Result.RUNNING;
                }
                if (!port.prepare(edge)) return finish(false, "prepare_rejected");
                if (edge.kind() == TraversalEdge.Kind.DOOR) return finish(true, "door_opened");
                begun = true; started = clock;
                port.begin(edge);
            }
            long duration = edge.kind() == TraversalEdge.Kind.FENCE ? 2_000_000_000L : 2_600_000_000L;
            if (clock - started > 6_000_000_000L) return finish(false, "timeout");
            if (!landed && clock - started >= duration * 3 / 5) {
                // One narrowly authorized transition; no intermediate position is left inside the barrier.
                port.land(edge); landed = true;
            }
            return clock - started >= duration ? finish(true, "landed") : Result.RUNNING;
        } catch (RuntimeException failure) {
            finish(false, "exception");
            throw failure;
        }
    }
    public void cancel() { if (edge != null) finish(false, "cancelled"); }
    private Result finish(boolean success, String why) {
        TraversalEdge previous = edge;
        boolean notify = begun, committed = landed;
        edge = null; begun = false; reason = why;
        reservations.release(lease); lease = null;
        try { if (notify) port.end(previous, committed); }
        finally { landed = false; }
        return success ? Result.SUCCEEDED : Result.FAILED;
    }
}
