package zombie.apocalipse.humans;

import java.util.Locale;
import java.util.Objects;
import zombie.iso.IsoDirections;

/** Immutable input to the factory; contains no connection or database identity. */
public record ServerHumanDefinition(
    String key, String username, String displayName, float x, float y, int z,
    IsoDirections facing, boolean female, String outfit
) {
    public ServerHumanDefinition {
        requireText(key, "key");
        requireText(username, "username");
        requireText(displayName, "displayName");
        requireText(outfit, "outfit");
        Objects.requireNonNull(facing, "facing");
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0
            || x >= Integer.MAX_VALUE || y >= Integer.MAX_VALUE || z < -32 || z > 31) {
            throw new IllegalArgumentException("Invalid human world coordinates");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        }
    }

    /** Opt-in startup guide at the configured Apocalipse location, with JVM overrides. */
    static ServerHumanDefinition configuredGuide() {
        if (!Boolean.getBoolean("apocbr.guide.enabled")) return null;
        String name = System.getProperty("apocbr.guide.name", "Apocalipse Guide");
        return new ServerHumanDefinition(
            "guide", name, name,
            Float.parseFloat(System.getProperty("apocbr.guide.x", "14294")),
            Float.parseFloat(System.getProperty("apocbr.guide.y", "11862")),
            Integer.parseInt(System.getProperty("apocbr.guide.z", "0")),
            IsoDirections.valueOf(System.getProperty("apocbr.guide.facing", "E").trim().toUpperCase(Locale.ROOT)),
            Boolean.getBoolean("apocbr.guide.female"),
            System.getProperty("apocbr.guide.outfit", "Tourist")
        );
    }
}
