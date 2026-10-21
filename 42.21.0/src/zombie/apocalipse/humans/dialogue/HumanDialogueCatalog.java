package zombie.apocalipse.humans.dialogue;

import java.util.Map;
import java.util.Objects;

/** Detached, immutable content published by the SQL worker, consumed on the simulation thread. */
public final class HumanDialogueCatalog {
    public record Snapshot(long generation, Map<String,Object> profiles) {
        public Snapshot { profiles = Map.copyOf(profiles); }
    }
    private static volatile Snapshot current;
    private HumanDialogueCatalog() { }
    public static Snapshot current() { return current; }
    public static synchronized void publish(Map<String,Object> profiles) {
        Objects.requireNonNull(profiles);
        if (current == null || !current.profiles().equals(profiles))
            current = new Snapshot(current == null ? 1 : current.generation()+1, profiles);
    }
}
