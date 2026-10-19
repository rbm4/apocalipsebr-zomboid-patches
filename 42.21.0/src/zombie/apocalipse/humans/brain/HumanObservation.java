package zombie.apocalipse.humans.brain;

import java.util.List;

/** Values only: never pass live engine objects into a brain. Time is monotonic. */
public record HumanObservation(long nowNanos, List<Visitor> visitors) {
    public HumanObservation {
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
