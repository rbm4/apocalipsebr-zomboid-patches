package zombie.apocalipse.humans.brain;

import java.util.List;

/** Values only: never pass live engine objects into a brain. Time is monotonic. */
public record HumanObservation(long nowNanos, List<Visitor> visitors, boolean movementAvailable,
                               zombie.apocalipse.humans.state.HumanState state, HumanSenses.Frame senses,HumanTerritory territory,HumanSenses.Frame awareness,Support support) {
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state,HumanSenses.Frame senses,HumanTerritory territory,HumanSenses.Frame awareness){this(nowNanos,visitors,movementAvailable,state,senses,territory,awareness,Support.NONE);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state,HumanSenses.Frame senses,HumanTerritory territory){this(nowNanos,visitors,movementAvailable,state,senses,territory,senses);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state,HumanSenses.Frame senses){this(nowNanos,visitors,movementAvailable,state,senses,HumanTerritory.UNAVAILABLE);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable,zombie.apocalipse.humans.state.HumanState state){this(nowNanos,visitors,movementAvailable,state,HumanSenses.Frame.EMPTY);}
    public HumanObservation(long nowNanos,List<Visitor> visitors,boolean movementAvailable){this(nowNanos,visitors,movementAvailable,zombie.apocalipse.humans.state.HumanState.INITIAL);}
    public HumanObservation(long nowNanos, List<Visitor> visitors) { this(nowNanos, visitors, false); }
    public HumanObservation {
        java.util.Objects.requireNonNull(state);
        java.util.Objects.requireNonNull(senses);
        java.util.Objects.requireNonNull(territory);
        java.util.Objects.requireNonNull(awareness);
        java.util.Objects.requireNonNull(support);
        visitors = List.copyOf(visitors);
    }

    /** World-captured nearby capable squadmates, bound to the owning actor's incarnation. */
    public record Support(long sampledAtNanos,int allies) {
        public static final Support NONE=new Support(0,0);
        public Support {if(allies<0||allies>7)throw new IllegalArgumentException("Invalid local squad strength");}
        public int freshAllies(long now){return now>=sampledAtNanos&&now-sampledAtNanos<=500_000_000L?allies:0;}
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
