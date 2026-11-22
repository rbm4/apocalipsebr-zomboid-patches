package zombie.apocalipse.humans.state;

import java.util.Objects;

/** Serialized mutations, immutable snapshots. Standalone stores retain their construction-thread owner. */
public final class HumanStateStore {
    public enum Status { APPLIED, NO_CHANGE, STALE_REVISION, STALE_ACTIVATION, INACTIVE, UNAVAILABLE, QUEUE_FULL }
    public record Result(Status status, HumanState state) { }
    private final Thread owner=Thread.currentThread();
    private final Runnable access;
    private volatile HumanState state;
    private boolean initialized;
    public HumanStateStore(HumanState initial){this(initial,null);}
    /** Factory-managed experimental simulation supplies its explicit server/worker access guard. */
    public HumanStateStore(HumanState initial,Runnable access){state=Objects.requireNonNull(initial);this.access=access;}
    public HumanState snapshot(){return state;}
    public synchronized Result dispatch(long expectedRevision,HumanMutation mutation){
        requireOwner();Objects.requireNonNull(mutation);
        initialized=true;
        if(expectedRevision!=state.revision())return new Result(Status.STALE_REVISION,state);
        var previous=state;
        var next=HumanStateReducer.reduce(previous,mutation);
        Status status=next==previous?Status.NO_CHANGE:Status.APPLIED;state=next;
        return new Result(status,state);
    }
    public synchronized Result dispatch(HumanMutation mutation){requireOwner();return dispatch(state.revision(),mutation);}
    /** Startup-only hydration, before this logical identity is activated. */
    public synchronized void restore(HumanState restored){
        requireOwner();if(initialized||!state.equals(HumanState.INITIAL))throw new IllegalStateException("State already initialized");
        state=Objects.requireNonNull(restored);initialized=true;
    }
    private void requireOwner(){if(access!=null){access.run();return;}if(Thread.currentThread()!=owner)throw new IllegalStateException("NPC state requires simulation-thread owner");}
}
