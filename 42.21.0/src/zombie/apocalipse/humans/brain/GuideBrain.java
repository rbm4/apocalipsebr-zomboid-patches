package zombie.apocalipse.humans.brain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Deterministic greeting policy. No world queries, threads, sleeps, or packet code. */
public final class GuideBrain extends HumanStateMachine<GuideBrain.State> {
    public enum State { IDLE, ROAMING, FACING_VISITOR, HOLDING_ATTENTION, SALUTING, SPEAKING, RETURNING_HOME, COOLDOWN, STOPPED }
    public static final long ROAM_PAUSE_NANOS = 3_000_000_000L;
    public static final long ATTENTION_NANOS = 1_000_000_000L;
    public static final long SALUTATION_NANOS = 2_500_000_000L;
    public static final long COOLDOWN_NANOS = 3_000_000_000L;
    public static final long VISITOR_COOLDOWN_NANOS = 120_000_000_000L;
    private static final int MAX_GREETING_HISTORY = 2048;
    private final Map<HumanObservation.Identity, Long> greeted = new HashMap<>();
    private HumanObservation.Identity target;
    private boolean started;

    public GuideBrain() { super(State.IDLE); }

    public void update(HumanObservation observation, HumanActuator actuator) {
        if (state() == State.STOPPED) return;
        long now = observation.nowNanos();
        if (!started) { enteredAt = now; started = true; }
        greeted.entrySet().removeIf(entry -> now - entry.getValue() >= VISITOR_COOLDOWN_NANOS);
        if (attending()) {
            if (observation.find(target) == null) {
                transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "visitor_lost", now, actuator);
            } else if (!trackTarget(actuator)) {
                transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "tracking_failed", now, actuator);
            }
        }
        if (state() == State.IDLE || state() == State.ROAMING) {
            HumanObservation.Visitor visitor = observation.visitors().stream()
                .filter(candidate -> !greeted.containsKey(candidate.identity()) && greeted.size() < MAX_GREETING_HISTORY)
                .min(Comparator.comparingDouble(HumanObservation.Visitor::distanceSquared)
                    .thenComparingInt(candidate -> candidate.identity().onlineId()))
                .orElse(null);
            if (visitor != null) {
                target = visitor.identity();
                greeted.put(target, now);
                transition(State.FACING_VISITOR, new HumanAction.FaceVisitor(target), "visitor_selected", now, actuator);
            } else if (state() == State.IDLE) {
                if (!observation.canRoam() || now - enteredAt < ROAM_PAUSE_NANOS) return;
                transition(State.ROAMING, new HumanAction.Roam(), "idle_roam_due", now, actuator);
            } else if (!observation.canRoam()) {
                transition(State.IDLE, null, "roaming_unavailable", now, actuator);
            }
        }
        updateAction(now, actuator);
        HumanActionExecution action = execution();
        if (action != null && action.terminal()) {
            if (state() == State.ROAMING) {
                transition(State.IDLE, null, action.status() == HumanActionExecution.Status.SUCCEEDED
                    ? "roam_complete" : "roam_blocked", now, actuator);
            } else if (state() == State.RETURNING_HOME) {
                target = null;
                transition(action.status() == HumanActionExecution.Status.SUCCEEDED ? State.COOLDOWN : State.STOPPED,
                    null, action.status() == HumanActionExecution.Status.SUCCEEDED ? "returned_home" : "home_restore_failed", now, actuator);
            } else if (action.status() != HumanActionExecution.Status.SUCCEEDED) {
                transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "action_failed", now, actuator);
            } else {
                switch (state()) {
                    case FACING_VISITOR -> transition(State.HOLDING_ATTENTION,
                        new HumanAction.HoldAttention(target, ATTENTION_NANOS), "facing_complete", now, actuator);
                    case HOLDING_ATTENTION -> transition(State.SALUTING,
                        new HumanAction.Emote("wavehi", SALUTATION_NANOS), "attention_complete", now, actuator);
                    case SALUTING -> transition(State.SPEAKING, new HumanAction.Say("hi"), "salutation_complete", now, actuator);
                    case SPEAKING -> {
                        greeted.put(target, now);
                        transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "greeting_complete", now, actuator);
                    }
                    default -> { }
                }
            }
        } else if (state() == State.COOLDOWN && now - enteredAt >= COOLDOWN_NANOS) {
            transition(State.IDLE, null, "cooldown_complete", now, actuator);
        }
    }

    public void stop(long now, HumanActuator actuator) {
        if (state() != State.STOPPED) transition(State.STOPPED, null, "actor_removed", now, actuator);
        target = null;
        greeted.clear();
    }

    private boolean attending() {
        return switch (state()) {
            case FACING_VISITOR, HOLDING_ATTENTION, SALUTING, SPEAKING -> true;
            default -> false;
        };
    }

    /** Attention is sustained independently of the current wave/speech action. */
    private boolean trackTarget(HumanActuator actuator) {
        try {
            // Resolve the same identity against the live world, never a captured point.
            return actuator.face(target);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    protected boolean permits(State from, State to) {
        if (to == State.STOPPED) return from != State.STOPPED;
        if (to == State.RETURNING_HOME) return switch (from) {
            case FACING_VISITOR, HOLDING_ATTENTION, SALUTING, SPEAKING -> true;
            default -> false;
        };
        return switch (from) {
            case IDLE -> to == State.FACING_VISITOR || to == State.ROAMING;
            case ROAMING -> to == State.IDLE || to == State.FACING_VISITOR;
            case FACING_VISITOR -> to == State.HOLDING_ATTENTION;
            case HOLDING_ATTENTION -> to == State.SALUTING;
            case SALUTING -> to == State.SPEAKING;
            case RETURNING_HOME -> to == State.COOLDOWN;
            case COOLDOWN -> to == State.IDLE;
            default -> false;
        };
    }
}
