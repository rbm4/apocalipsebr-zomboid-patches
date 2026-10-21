package zombie.apocalipse.humans.brain;

import java.util.List;
import java.util.Objects;

/** Immutable sensor facts, independent of hostility, target choice and physical state. */
public final class HumanSenses {
    private HumanSenses() { }
    public enum Kind { PLAYER, ZOMBIE }
    public enum Coverage { COMPLETE, PARTIAL, UNAVAILABLE }
    /** Generation is local to the owning sensor; never persist or use as a network ID. */
    public record Identity(Kind kind, short onlineId, int instanceId, long generation) {
        public Identity { Objects.requireNonNull(kind); }
    }
    public record Sighting(Identity identity,float x,float y,float z,double distanceSquared) { }
    /** Coordinates/distance are last observed values when visible is false. */
    public record Contact(Identity identity,float x,float y,float z,double distanceSquared,
                          long firstSeenNanos,long lastSeenNanos,boolean visible) { }
    public record Work(int playerCandidates,int tiles,int bodies,int zombieCandidates,int sightChecks) { }
    public record Frame(boolean sampled,long sampledAtNanos,List<Contact> contacts,
                        Coverage players,Coverage zombies,boolean contactsTruncated,Work work) {
        public static final Frame EMPTY=new Frame(false,0,List.of(),Coverage.UNAVAILABLE,Coverage.UNAVAILABLE,false,new Work(0,0,0,0,0));
        public Frame { contacts=List.copyOf(contacts);Objects.requireNonNull(players);Objects.requireNonNull(zombies);Objects.requireNonNull(work); }
        public List<Contact> visible(Kind kind){return contacts.stream().filter(c->c.visible()&&c.identity().kind()==kind).toList();}
    }
}
