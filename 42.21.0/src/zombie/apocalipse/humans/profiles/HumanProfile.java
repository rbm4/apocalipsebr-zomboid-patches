package zombie.apocalipse.humans.profiles;

import java.util.Map;
import java.util.Objects;

/** Persistence-ready configuration data; contains no Java objects, timers or world references. */
public record HumanProfile(String id, int revision, String behaviorId, Map<String, Long> parameters,
                           String greetingKey, String announcementId, boolean dialogue, boolean trading) {
    public HumanProfile {
        identifier(id); identifier(behaviorId); identifier(announcementId);
        if (revision < 1) throw new IllegalArgumentException("Invalid profile revision");
        parameters = Map.copyOf(parameters);
        if (parameters.size() > 16) throw new IllegalArgumentException("Too many profile parameters");
        for (var entry : parameters.entrySet()) {
            identifier(entry.getKey());
            if (entry.getValue() < 0 || entry.getValue() > 86400000L) throw new IllegalArgumentException("Invalid parameter milliseconds");
        }
        Objects.requireNonNull(greetingKey);
        if (greetingKey.length() > 128) throw new IllegalArgumentException("Greeting key too long");
    }
    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid profile identifier");
    }
}
