package zombie.apocalipse.humans.healing;

import java.util.Map;
import java.util.Set;

/** Immutable opt-in module configuration; supplies are per incarnation, never replenished by a tick. */
public record HealingSettings(int bandages, long bandageMs, long sheetsMs, long quietMs,
                              int clearTiles, float recoveryPerSecond) {
    public static HealingSettings from(Map<String,Long> values) {
        if (!Set.of("bandages", "bandageMs", "sheetsMs", "quietMs", "clearTiles", "recoveryPermillePerSecond").containsAll(values.keySet()))
            throw new IllegalArgumentException("Unknown healing parameter");
        return new HealingSettings(Math.toIntExact(values.getOrDefault("bandages",3L)),
            values.getOrDefault("bandageMs",4200L), values.getOrDefault("sheetsMs",5600L),
            values.getOrDefault("quietMs",5000L), Math.toIntExact(values.getOrDefault("clearTiles",6L)),
            values.getOrDefault("recoveryPermillePerSecond",2L)/1000F);
    }
    public HealingSettings {
        if (bandages<0 || bandages>32 || bandageMs<1000 || bandageMs>60000 || sheetsMs<1000 || sheetsMs>60000
            || quietMs<1000 || quietMs>120000 || clearTiles<2 || clearTiles>32
            || !Float.isFinite(recoveryPerSecond) || recoveryPerSecond<0 || recoveryPerSecond>.01F)
            throw new IllegalArgumentException("Invalid healing settings");
    }
}
