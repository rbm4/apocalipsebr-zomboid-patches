package zombie.apocalipse.humans;

/** Logical NPC state survives presentation removal. Persistence never contains the presentation actor. */
public final class HumanLifecycle {
    private final Runnable stateAccess;
    private zombie.apocalipse.humans.state.HumanStateStore physiology;
    private zombie.apocalipse.humans.state.HumanStateStore previousLife;
    private zombie.apocalipse.humans.death.HumanDeathState death=zombie.apocalipse.humans.death.HumanDeathState.NONE;
    public zombie.apocalipse.humans.death.HumanDeathState death(){return death;}
    void restoreDeath(zombie.apocalipse.humans.death.HumanDeathState value){death=java.util.Objects.requireNonNull(value);}
    void respawning(long now){
        if(state!=State.DEAD||!definition.deathPolicy().enabled()||!death.due(now))throw new IllegalStateException("Respawn is not due");
        previousLife=physiology;
        var healthy=zombie.apocalipse.humans.state.HumanState.INITIAL;
        physiology=new zombie.apocalipse.humans.state.HumanStateStore(new zombie.apocalipse.humans.state.HumanState(
            Math.addExact(previousLife.snapshot().revision(),1),healthy.vitals(),healthy.injuries()),stateAccess);state=State.SPAWNING;
    }
    void spawnFailed(){
        if(state!=State.SPAWNING)throw new IllegalStateException("Not spawning");
        if(previousLife!=null){physiology=previousLife;previousLife=null;state=State.DEAD;}else state=State.DORMANT;
    }
    public zombie.apocalipse.humans.state.HumanStateStore physiology(){return physiology;}
    public enum State { DORMANT, SPAWNING, ACTIVE, DEAD }
    public record Snapshot(ServerHumanDefinition definition, State state, int activations, int profileRevision) { }
    public Snapshot snapshot() { return new Snapshot(definition,state,activations,profileRevision); }
    private final ServerHumanDefinition definition;
    private State state = State.DORMANT;
    private int activations;
    private int profileRevision;
    HumanLifecycle(ServerHumanDefinition definition) { this(definition,null); }
    HumanLifecycle(ServerHumanDefinition definition,Runnable stateAccess) {
        this.definition = definition;this.stateAccess=stateAccess;
        physiology=new zombie.apocalipse.humans.state.HumanStateStore(zombie.apocalipse.humans.state.HumanState.INITIAL,stateAccess);
    }
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
        state = State.ACTIVE; activations++; profileRevision = revision;previousLife=null;death=zombie.apocalipse.humans.death.HumanDeathState.NONE;
    }
    void dormant() {
        if (state != State.ACTIVE && state != State.SPAWNING) throw new IllegalStateException("Cannot deactivate NPC");
        state = State.DORMANT;
    }
    void dead() {
        dead(System.currentTimeMillis());
    }
    void dead(long now) {
        if (state != State.ACTIVE) throw new IllegalStateException("Only active NPC can die");
        state = State.DEAD;
        death=zombie.apocalipse.humans.death.HumanDeathState.died(now,definition.deathPolicy());
    }
}
