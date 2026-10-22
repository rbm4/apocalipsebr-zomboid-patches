package zombie.apocalipse.humans.brain;

import java.util.Set;

/** Observation of a defended area, not faction ownership or permission to traverse it. */
public record HumanTerritory(boolean available,boolean actorInside,Set<HumanSenses.Identity> contactsInside) {
    public static final HumanTerritory UNAVAILABLE=new HumanTerritory(false,false,Set.of());
    public HumanTerritory { contactsInside=Set.copyOf(contactsInside); }
    public boolean contains(HumanSenses.Identity identity){return available&&contactsInside.contains(identity);}
}
