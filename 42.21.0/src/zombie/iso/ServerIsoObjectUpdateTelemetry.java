package zombie.iso;

import zombie.ApocBRServerTelemetryLite;

/** Fixed class families; timers/counters are flushed once per ProcessIsoObject pass. */
public final class ServerIsoObjectUpdateTelemetry {
    private static final String[] TYPES = { "IsoThumpable", "IsoCompost", "IsoFeedingTrough", "IsoStove", "IsoGenerator", "IsoTrap",
        "IsoBarbecue", "IsoFireplace", "IsoCarBatteryCharger", "IsoClothingWasher", "IsoClothingDryer",
        "IsoCombinationWasherDryer", "IsoStackedWasherDryer", "other" };
    private static final ClassValue<Integer> KIND = new ClassValue<>() {
        @Override protected Integer computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                String name = current.getName();
                for (int i = 0; i < TYPES.length - 1; i++) {
                    if (name.equals("zombie.iso.objects." + TYPES[i])) return i;
                }
            }
            return TYPES.length - 1;
        }
    };
    public static int kind(IsoObject object) { return KIND.get(object.getClass()); }
    public static int size() { return TYPES.length; }
    public static void flush(long[] nanos, long[] attempts) {
        for (int i = 0; i < TYPES.length; i++) {
            if (attempts[i] == 0L) continue;
            ApocBRServerTelemetryLite.recordPhase("simulation.isoObjects.update." + TYPES[i], nanos[i]);
            ApocBRServerTelemetryLite.count("isoObjects.updateAttempts." + TYPES[i], attempts[i]);
        }
    }
}
