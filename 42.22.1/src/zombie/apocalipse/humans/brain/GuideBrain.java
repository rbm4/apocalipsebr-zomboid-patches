package zombie.apocalipse.humans.brain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;
import zombie.apocalipse.humans.profiles.GuidePolicy;

/** Deterministic greeting policy. No world queries, threads, sleeps, or packet code. */
public final class GuideBrain extends HumanStateMachine<GuideBrain.State> implements HumanModule {
    public enum State { IDLE, ROAMING, FACING_VISITOR, HOLDING_ATTENTION, SALUTING, SPEAKING, SUSPENDED, RETURNING_HOME, COOLDOWN, STOPPED }
    private static final int MAX_GREETING_HISTORY = 2048;
    private final Map<HumanObservation.Identity, Long> greeted = new HashMap<>();
    private HumanObservation.Identity target;
    private boolean started;
    private boolean roamingEnabled=true;
    private final HumanAnnouncementProvider announcements;

    private final GuidePolicy policy;
    private final Supplier<String> greeting;
    public GuideBrain(GuidePolicy policy, Supplier<String> greeting, HumanAnnouncementProvider announcements) {
        super(State.IDLE);
        this.announcements = Objects.requireNonNull(announcements);
        this.policy = Objects.requireNonNull(policy);
        this.greeting = Objects.requireNonNull(greeting);
    }

    /** Greeting module variant shares the tested attention/speech cycle, without owning roaming. */
    public GuideBrain greetingOnly() { roamingEnabled=false;return this; }
    public boolean eligible(HumanObservation observation) {
        if(!available()||state()==State.SUSPENDED)return false;
        if(state()!=State.IDLE&&state()!=State.ROAMING)return true;
        long now=observation.nowNanos();
        return observation.visitors().stream().anyMatch(v ->
            (!greeted.containsKey(v.identity())||now-greeted.get(v.identity())>=policy.visitorCooldown())
            && (greeted.size()<MAX_GREETING_HISTORY||greeted.containsKey(v.identity())));
    }
    public void interrupt(long now,HumanActuator actuator) {
        if(state()==State.IDLE)return;
        suspend(now,actuator);resume(null,now,actuator);
    }

    public void update(HumanObservation observation, HumanActuator actuator) {
        if (state() == State.STOPPED || state() == State.SUSPENDED) return;
        long now = observation.nowNanos();
        if (!started) { enteredAt = now; started = true; }
        greeted.entrySet().removeIf(entry -> now - entry.getValue() >= policy.visitorCooldown());
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
                if (!roamingEnabled || !observation.movementAvailable() || now - enteredAt < policy.roamPause()) return;
                transition(State.ROAMING, new HumanAction.Move(new zombie.apocalipse.humans.walking.MovementRequest.Pattern("background")), "idle_roam_due", now, actuator);
            } else if (!observation.movementAvailable()) {
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
                        new HumanAction.HoldAttention(target, policy.attention()), "facing_complete", now, actuator);
                    case HOLDING_ATTENTION -> beginSalutation(now, actuator);
                    case SALUTING -> beginAnnouncement(now, actuator);
                    case SPEAKING -> {
                        greeted.put(target, now);
                        transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "greeting_complete", now, actuator);
                    }
                    default -> { }
                }
            }
        } else if (state() == State.COOLDOWN && now - enteredAt >= policy.cooldown()) {
            transition(State.IDLE, null, "cooldown_complete", now, actuator);
        }
    }

    private void beginSalutation(long now, HumanActuator actuator) {
        HumanAction plan;
        try {
            String text = greeting.get();
            HumanAction wave = new HumanAction.Emote("wavehi", policy.salutation());
            plan = text == null ? wave : new HumanAction.Parallel(List.of(wave, new HumanAction.Say(text)));
        } catch (RuntimeException failure) {
            transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "greeting_unavailable", now, actuator);
            return;
        }
        transition(State.SALUTING, plan, "attention_complete", now, actuator);
    }

    private void beginAnnouncement(long now, HumanActuator actuator) {
        HumanAction.Sequence plan;
        try {
            // Resolve content once. Mutable event state must not change mid-plan.
            plan = HumanAction.Sequence.speech(announcements.lines(), policy.announcementPause());
        } catch (RuntimeException failure) {
            transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "announcement_unavailable", now, actuator);
            return;
        }
        transition(State.SPEAKING, plan, "salutation_complete", now, actuator);
    }

    public void stop(long now, HumanActuator actuator) {
        if (state() != State.STOPPED) transition(State.STOPPED, null, "actor_removed", now, actuator);
        target = null;
        greeted.clear();
    }

    public boolean available() { return state() != State.STOPPED; }

    public void suspend(long now, HumanActuator actuator) {
        if (!available() || state() == State.SUSPENDED) throw new IllegalStateException("Behavior cannot suspend");
        transition(State.SUSPENDED, null, "control_taken", now, actuator);
        actuator.stopMoving();
    }

    public void resume(HumanObservation.Identity visitor, long now, HumanActuator actuator) {
        if (state() != State.SUSPENDED) return;
        if (visitor != null && (greeted.containsKey(visitor) || greeted.size() < MAX_GREETING_HISTORY)) greeted.put(visitor, now);
        target = null;
        transition(State.RETURNING_HOME, new HumanAction.ReturnHome(), "control_released", now, actuator);
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
        if (to == State.SUSPENDED) return from != State.STOPPED && from != State.SUSPENDED;
        if (to == State.RETURNING_HOME) return switch (from) {
            case FACING_VISITOR, HOLDING_ATTENTION, SALUTING, SPEAKING, SUSPENDED -> true;
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
