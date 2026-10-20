package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** One action instance: start once, poll without blocking, settle once. */
public final class HumanActionExecution {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    private final HumanAction action;
    private Status status = Status.PENDING;
    private long startedAt;
    private String failure;

    public HumanActionExecution(HumanAction action) { this.action = Objects.requireNonNull(action); }
    public HumanAction action() { return action; }
    public Status status() { return status; }
    public String failure() { return failure; }
    public boolean terminal() { return status != Status.PENDING && status != Status.RUNNING; }

    public void update(long now, HumanActuator actuator) {
        if (terminal()) return;
        try {
            if (status == Status.PENDING) {
                startedAt = now;
                status = Status.RUNNING;
                switch (action) {
                    case HumanAction.Roam ignored -> {
                        if (!actuator.startRoaming(now)) { actuator.stopMoving(); settle(false); }
                    }
                    case HumanAction.FaceVisitor face -> settle(actuator.face(face.visitor()));
                    case HumanAction.Say say -> settle(actuator.say(say.text()));
                    case HumanAction.ReturnHome ignored -> settle(actuator.returnHome());
                    case HumanAction.Emote emote -> {
                        if (!actuator.startEmote(emote.name())) {
                            stopSafely(actuator);
                            settle(false);
                        }
                    }
                    case HumanAction.HoldAttention ignored -> { }
                    case HumanAction.WaitForTrade ignored -> { }
                }
            }
            if (status != Status.RUNNING) return;
            switch (action) {
                case HumanAction.WaitForTrade wait -> {
                    if (!actuator.face(wait.visitor())) settle(false);
                }
                case HumanAction.Roam ignored -> {
                    HumanActuator.MotionStatus result = actuator.updateRoaming(now);
                    if (result != HumanActuator.MotionStatus.RUNNING) {
                        actuator.stopMoving();
                        settle(result == HumanActuator.MotionStatus.SUCCEEDED);
                    }
                }
                case HumanAction.HoldAttention hold -> {
                    if (!actuator.face(hold.visitor())) settle(false);
                    else if (now - startedAt >= hold.durationNanos()) settle(true);
                }
                case HumanAction.Emote emote -> {
                    if (now - startedAt >= emote.durationNanos()) {
                        actuator.stopEmote();
                        settle(true);
                    }
                }
                default -> { }
            }
        } catch (RuntimeException exception) {
            // A failed port call must not repeat one-shot speech/animation every tick.
            failure = exception.getClass().getSimpleName();
            if (action instanceof HumanAction.Emote) stopSafely(actuator);
            if (action instanceof HumanAction.Roam) stopMovementSafely(actuator);
            status = Status.FAILED;
        }
    }

    public void cancel(HumanActuator actuator) {
        if (terminal()) return;
        if (status == Status.RUNNING && action instanceof HumanAction.Emote) stopSafely(actuator);
        if (status == Status.RUNNING && action instanceof HumanAction.Roam) stopMovementSafely(actuator);
        status = Status.CANCELLED;
    }

    private void stopSafely(HumanActuator actuator) {
        try { actuator.stopEmote(); } catch (RuntimeException ignored) { /* Preserve terminal status. */ }
    }

    private void stopMovementSafely(HumanActuator actuator) {
        try { actuator.stopMoving(); } catch (RuntimeException ignored) { /* Preserve terminal status. */ }
    }

    private void settle(boolean success) {
        status = success ? Status.SUCCEEDED : Status.FAILED;
        if (!success) failure = "precondition_or_transport_unavailable";
    }
}
