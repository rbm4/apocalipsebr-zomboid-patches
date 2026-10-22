package zombie.apocalipse.humans.brain;

import java.util.Objects;

/** Single owner of autonomous versus exclusive interaction control. */
public final class HumanActivityController {
    public enum Mode { AUTONOMOUS, DIALOGUE, TRADE, RECORDING_SALE, STOPPED }
    private final HumanBehavior behavior;
    private final boolean dialogue, trade;
    private String failure;
    private boolean blocked;
    public String failure() { return failure; }
    private Mode mode = Mode.AUTONOMOUS;
    private HumanObservation.Identity visitor;
    public HumanActivityController(HumanBehavior behavior, boolean dialogue, boolean trade) {
        this.behavior = Objects.requireNonNull(behavior); this.dialogue = dialogue; this.trade = trade;
    }
    public Mode mode() { return mode; }
    public HumanBehavior behavior() { return behavior; }
    public void update(long now, java.util.function.LongFunction<HumanObservation> perceive, HumanActuator actuator) {
        update(now,perceive,actuator,zombie.apocalipse.humans.state.HumanState.INITIAL);
    }
    public void update(long now,java.util.function.LongFunction<HumanObservation> perceive,HumanActuator actuator,
                       zombie.apocalipse.humans.state.HumanState state) {
        if (mode == Mode.STOPPED || blocked) return;
        try {
            update(mode == Mode.AUTONOMOUS && behavior.needsPerception() ? perceive.apply(now)
                : new HumanObservation(now,java.util.List.of(),false,state), actuator);
        } catch (RuntimeException problem) { fail(problem,now,actuator); }
    }
    public void update(HumanObservation observation, HumanActuator actuator) {
        if(blocked)return;
        try {
            if (mode == Mode.AUTONOMOUS) behavior.update(observation, actuator);
            else if (mode == Mode.TRADE || mode == Mode.DIALOGUE) actuator.face(visitor);
        } catch (RuntimeException problem) { fail(problem,observation.nowNanos(),actuator); }
    }
    private boolean begin(Mode next, HumanObservation.Identity visitor, long now, HumanActuator actuator) {
        Objects.requireNonNull(visitor);
        if (mode != Mode.AUTONOMOUS || blocked) return false;
        try {
            if (!behavior.available()) return false;
            behavior.suspend(now, actuator);
        }
        catch (RuntimeException problem) { fail(problem,now,actuator); return false; }
        this.visitor = visitor; mode = next;
        return true;
    }
    public boolean beginDialogue(HumanObservation.Identity visitor, long now, HumanActuator actuator) {
        return dialogue && begin(Mode.DIALOGUE, visitor, now, actuator);
    }
    public boolean beginTrade(HumanObservation.Identity visitor, long now, HumanActuator actuator) {
        return trade && begin(Mode.TRADE, visitor, now, actuator);
    }
    public void recordingSale(long now, HumanActuator actuator) {
        if (mode != Mode.TRADE) throw new IllegalStateException("No owned trade");
        mode = Mode.RECORDING_SALE;
    }
    private void finish(long now, HumanActuator actuator) {
        try {
            behavior.resume(visitor, now, actuator);
            visitor = null; mode = Mode.AUTONOMOUS;
        } catch (RuntimeException problem) { fail(problem,now,actuator); }
    }
    public void finishDialogue(long now, HumanActuator actuator) {
        if (mode == Mode.DIALOGUE) finish(now, actuator);
    }
    public void finishTrade(long now, HumanActuator actuator) {
        if (mode == Mode.TRADE || mode == Mode.RECORDING_SALE) finish(now, actuator);
    }
    /** Generic external availability gate; callers close any exclusive lease before blocking. */
    public void setBlocked(boolean value,long now,HumanActuator actuator) {
        if(blocked==value||mode==Mode.STOPPED)return;
        if(mode!=Mode.AUTONOMOUS)throw new IllegalStateException("Release exclusive activity before blocking");
        blocked=value;
        try {
            if(value){behavior.suspend(now,actuator);actuator.stopMoving();actuator.stopEmote();}
            else behavior.resume(null,now,actuator);
        }catch(RuntimeException problem){fail(problem,now,actuator);}
    }
    public void stop(long now, HumanActuator actuator) {
        if (mode == Mode.STOPPED) return;
        mode = Mode.STOPPED; visitor = null;
        try { behavior.stop(now, actuator); } catch (RuntimeException problem) { if (failure == null) failure = problem.getClass().getSimpleName(); }
        try { actuator.stopMoving(); } catch (RuntimeException ignored) { }
        try { actuator.stopEmote(); } catch (RuntimeException ignored) { }
    }
    private void fail(RuntimeException problem,long now,HumanActuator actuator) {
        failure = problem.getClass().getSimpleName(); stop(now,actuator);
    }
}
