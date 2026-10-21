package zombie.apocalipse.humans.state;

import java.util.Objects;

/** Single writer, immutable snapshots. Lifetime belongs to logical identity, not IsoPlayer. */
public final class HumanStateStore {
    @FunctionalInterface public interface Observer {
        void applied(HumanState previous, HumanMutation mutation, HumanState next);
    }
    public enum Status { APPLIED, NO_CHANGE, STALE_REVISION, STALE_ACTIVATION, INACTIVE, UNAVAILABLE, QUEUE_FULL }
    public record Result(Status status, HumanState state) { }
    private final Thread owner=Thread.currentThread();
    private volatile HumanState state;
    private final Observer observer;
    private boolean initialized;
    public HumanStateStore(HumanState initial){this(initial,(previous,mutation,next)->{});}
    public HumanStateStore(HumanState initial,Observer observer){
        state=Objects.requireNonNull(initial);this.observer=Objects.requireNonNull(observer);
    }
    public HumanState snapshot(){return state;}
    public Result dispatch(long expectedRevision,HumanMutation mutation){
        requireOwner();Objects.requireNonNull(mutation);
        initialized=true;
        if(expectedRevision!=state.revision())return new Result(Status.STALE_REVISION,state);
        var previous=state;
        var next=HumanStateReducer.reduce(previous,mutation);
        Status status=next==previous?Status.NO_CHANGE:Status.APPLIED;state=next;
        if(status==Status.APPLIED)observer.applied(previous,mutation,next);
        return new Result(status,state);
    }
    public Result dispatch(HumanMutation mutation){requireOwner();return dispatch(state.revision(),mutation);}
    /** Startup-only hydration, before this logical identity is activated. */
    public void restore(HumanState restored){
        requireOwner();if(initialized||!state.equals(HumanState.INITIAL))throw new IllegalStateException("State already initialized");
        state=Objects.requireNonNull(restored);initialized=true;
    }
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("NPC state requires simulation-thread owner");}
}
