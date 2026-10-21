package zombie.apocalipse.humans.profiles;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import zombie.apocalipse.humans.brain.HumanBehavior;

/** Data profiles select allowlisted behavior factories, never executable database code. */
public final class HumanProfileRegistry {
    private record Factory(Consumer<HumanProfile> validate, Function<HumanProfile, HumanBehavior> create) { }
    private final Map<String, Factory> factories = new HashMap<>();
    private final Map<String, HumanProfile> profiles = new HashMap<>();
    public synchronized void registerBehavior(String id, Consumer<HumanProfile> validate, Function<HumanProfile, HumanBehavior> create) {
        HumanProfile.identifier(id);
        if (factories.containsKey(id)) throw new IllegalArgumentException("Duplicate behavior: " + id);
        factories.put(id,new Factory(Objects.requireNonNull(validate),Objects.requireNonNull(create)));
    }
    private void validate(HumanProfile profile) {
        Objects.requireNonNull(profile);
        Factory factory = factories.get(profile.behaviorId());
        if (factory == null) throw new IllegalArgumentException("Unknown behavior: " + profile.behaviorId());
        factory.validate.accept(profile);
    }
    public synchronized void register(HumanProfile profile) {
        validate(profile);
        if (profiles.containsKey(profile.id())) throw new IllegalArgumentException("Duplicate profile: " + profile.id());
        profiles.put(profile.id(),profile);
    }
    public synchronized void replace(HumanProfile profile) {
        validate(profile);
        HumanProfile previous = require(profile.id());
        if (profile.revision() <= previous.revision()) throw new IllegalArgumentException("Profile revision must advance");
        profiles.put(profile.id(),profile);
    }
    public synchronized HumanProfile require(String id) {
        HumanProfile profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown profile: " + id);
        return profile;
    }
    public synchronized HumanBehavior create(HumanProfile profile) {
        validate(profile);
        return Objects.requireNonNull(factories.get(profile.behaviorId()).create.apply(profile));
    }
}
