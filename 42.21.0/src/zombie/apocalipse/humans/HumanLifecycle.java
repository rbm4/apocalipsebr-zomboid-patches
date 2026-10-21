package zombie.apocalipse.humans;

/** Logical NPC state survives presentation removal. PostgreSQL storage comes later. */
public final class HumanLifecycle {
    public enum State { DORMANT, SPAWNING, ACTIVE, DEAD }
    public record Snapshot(ServerHumanDefinition definition, State state, int activations, int profileRevision) { }
    public Snapshot snapshot() { return new Snapshot(definition,state,activations,profileRevision); }
    private final ServerHumanDefinition definition;
    private State state = State.DORMANT;
    private int activations;
    private int profileRevision;
    HumanLifecycle(ServerHumanDefinition definition) { this.definition = definition; }
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
