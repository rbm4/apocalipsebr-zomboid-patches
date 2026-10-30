package zombie.apocalipse.humans.profiles;

import java.util.Map;
import java.util.Set;

/** Guide policy configuration uses database-friendly millisecond values. */
public record GuidePolicy(long roamPause, long attention, long salutation, long cooldown,
                          long visitorCooldown, long announcementPause) {
    public GuidePolicy {
        for (long value : new long[]{roamPause,attention,salutation,cooldown,visitorCooldown,announcementPause})
            if (value < 0 || value > 86400000000000L) throw new IllegalArgumentException("Invalid guide duration");
        if (salutation == 0) throw new IllegalArgumentException("Salutation must be positive");
    }
    private static final Set<String> KEYS = Set.of("roamPauseMs", "attentionMs", "salutationMs", "cooldownMs", "visitorCooldownMs", "announcementPauseMs");
    public static GuidePolicy from(Map<String, Long> values) {
        for (long value : values.values()) if (value < 0 || value > 86400000L) throw new IllegalArgumentException("Invalid guide milliseconds");
        if (!KEYS.containsAll(values.keySet())) throw new IllegalArgumentException("Unknown guide parameter");
        long salutation = values.getOrDefault("salutationMs", 2500L);
        if (salutation == 0) throw new IllegalArgumentException("Salutation must be positive");
        return new GuidePolicy(ms(values,"roamPauseMs",3000),ms(values,"attentionMs",1000),salutation*1000000,
            ms(values,"cooldownMs",3000),ms(values,"visitorCooldownMs",120000),ms(values,"announcementPauseMs",4000));
    }
    private static long ms(Map<String, Long> values,String key,long fallback) {
        return values.getOrDefault(key,fallback)*1000000;
    }
    public static GuidePolicy defaults() { return from(Map.of()); }
}
