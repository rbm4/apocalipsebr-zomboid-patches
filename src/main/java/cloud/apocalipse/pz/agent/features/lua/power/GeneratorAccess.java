package cloud.apocalipse.pz.agent.features.lua.power;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import zombie.iso.objects.IsoGenerator;

/** Narrow access to original private mechanics; no additional native members. */
public final class GeneratorAccess {
    private static final Field SURROUNDING;
    private static final Method AGE_FOOD;
    private static final Field LAST_HOUR;
    static {
        try {
            SURROUNDING = IsoGenerator.class.getDeclaredField("updateSurrounding");
            AGE_FOOD = IsoGenerator.class.getDeclaredMethod("updateFridgeFreezerItems");
            LAST_HOUR = IsoGenerator.class.getDeclaredField("lastHour");
            LAST_HOUR.setAccessible(true);
            SURROUNDING.setAccessible(true);
            AGE_FOOD.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
    private GeneratorAccess() { }
    public static int hour(IsoGenerator generator) {
        try { return LAST_HOUR.getInt(generator); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    public static void resetHour(IsoGenerator generator) {
        try { LAST_HOUR.setInt(generator,(int)zombie.GameTime.getInstance().getWorldAgeHours()); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    public static void updateElectricity(IsoGenerator generator) {
        try {
            if (SURROUNDING.getBoolean(generator) && generator.getSquare() != null) {
                generator.setSurroundingElectricity();
                SURROUNDING.setBoolean(generator, false);
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
    static void ageFood(IsoGenerator generator) {
        try { AGE_FOOD.invoke(generator); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
