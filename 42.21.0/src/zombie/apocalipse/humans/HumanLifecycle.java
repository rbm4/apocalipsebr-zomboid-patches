package zombie.apocalipse.humans;

/** Logical NPC state survives presentation removal. Persistence never contains the presentation actor. */
public final class HumanLifecycle {
    private final zombie.apocalipse.humans.state.HumanStateStore physiology = new zombie.apocalipse.humans.state.HumanStateStore(zombie.apocalipse.humans.state.HumanState.INITIAL);
    public zombie.apocalipse.humans.state.HumanStateStore physiology(){return physiology;}
    public enum State { DORMANT, SPAWNING, ACTIVE, DEAD }
    public record Snapshot(ServerHumanDefinition definition, State state, int activations, int profileRevision) { }
    public Snapshot snapshot() { return new Snapshot(definition,state,activations,profileRevision); }
    private final ServerHumanDefinition definition;
    private State state = State.DORMANT;
    private int activations;
    private int profileRevision;
    HumanLifecycle(ServerHumanDefinition definition) { this.definition = definition; }
    void restore(State persisted,int count,int revision) {
        if(state!=State.DORMANT||activations!=0)throw new IllegalStateException("Already initialized");
        state=persisted==State.DEAD?State.DEAD:State.DORMANT;activations=count;profileRevision=revision;
    }
    public ServerHumanDefinition definition() { return definition; }
    public State state() { return state; }
    public int activations() { return activations; }
    void spawning() {
        if (state != State.DORMANT) throw new IllegalStateException("NPC is not dormant");
        state = State.SPAWNING;
    }
    void active(int revision) {
        if (state != State.SPAWNING) throw new IllegalStateException("NPC is not spawning");
        state = State.ACTIVE; activations++; profileRevision = revision;
    }
    void dormant() {
        if (state != State.ACTIVE && state != State.SPAWNING) throw new IllegalStateException("Cannot deactivate NPC");
        state = State.DORMANT;
    }
    void dead() {
        if (state != State.ACTIVE) throw new IllegalStateException("Only active NPC can die");
        state = State.DEAD;
    }
}
