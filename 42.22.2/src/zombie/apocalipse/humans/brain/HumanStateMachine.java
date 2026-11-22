package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** Reusable action-owning state-machine base with explicit, inspectable transitions. */
public abstract class HumanStateMachine<S extends Enum<S>> {
    public record Transition<S>(S from, S to, String reason, long atNanos) { }
    private S state;
    private Transition<S> lastTransition;
    private HumanActionExecution execution;
    protected long enteredAt;

    protected HumanStateMachine(S initial) { state = Objects.requireNonNull(initial); }
    public final S state() { return state; }
    public final Transition<S> lastTransition() { return lastTransition; }
    public final HumanActionExecution execution() { return execution; }
    protected abstract boolean permits(S from, S to);

    protected final void transition(S next, HumanAction action, String reason, long now, HumanActuator actuator) {
        if (!permits(state, next)) throw new IllegalStateException("Illegal brain transition: " + state + " -> " + next);
        if (execution != null) execution.cancel(actuator);
        lastTransition = new Transition<>(state, next, reason, now);
        state = next;
        enteredAt = now;
        execution = action == null ? null : new HumanActionExecution(action);
    }

    protected final void updateAction(long now, HumanActuator actuator) {
        if (execution != null) execution.update(now, actuator);
    }
}
