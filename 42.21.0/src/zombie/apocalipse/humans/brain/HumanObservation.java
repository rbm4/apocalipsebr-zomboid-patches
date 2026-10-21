package zombie.apocalipse.humans.brain;

import java.util.List;

/** Values only: never pass live engine objects into a brain. Time is monotonic. */
public record HumanObservation(long nowNanos, List<Visitor> visitors, boolean movementAvailable,
                               zombie.apocalipse.humans.state.HumanState state, HumanSenses.Frame senses,HumanTerritory territory) {
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state,HumanSenses.Frame senses){this(nowNanos,visitors,movementAvailable,state,senses,HumanTerritory.UNAVAILABLE);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state){this(nowNanos,visitors,movementAvailable,state,HumanSenses.Frame.EMPTY);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable){this(nowNanos,visitors,movementAvailable,zombie.apocalipse.humans.state.HumanState.INITIAL);}
    public HumanObservation(long nowNanos, List<Visitor> visitors) { this(nowNanos, visitors, false); }
    public HumanObservation {
        java.util.Objects.requireNonNull(state);
        java.util.Objects.requireNonNull(senses);
        java.util.Objects.requireNonNull(territory);
        visitors = List.copyOf(visitors);
    }

    public record Identity(short onlineId, int instanceId) {
    }

    public record Visitor(Identity identity, float x, float y, float z, double distanceSquared) {
    }

    public Visitor find(Identity identity) {
        for (Visitor visitor : visitors) if (visitor.identity().equals(identity)) return visitor;
        return null;
    }
}
