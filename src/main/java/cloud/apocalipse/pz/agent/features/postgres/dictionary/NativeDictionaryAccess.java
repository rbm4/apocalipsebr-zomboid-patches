package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.List;

/** Original-JAR protected members stay in their signed packages; the agent owns the adapter. */
final class NativeDictionaryAccess {
    private NativeDictionaryAccess() { }

    static Object get(Object owner, String name) {
        try {
            Field field = field(owner instanceof Class<?> type ? type : owner.getClass(), name);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Original dictionary field unavailable: " + name);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object create(String name) {
        try { return Class.forName("zombie.world." + name).getConstructor().newInstance(); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Original dictionary type unavailable: " + name); }
    }

    static void set(Object owner, String name, Object value) {
        try { field(owner.getClass(), name).set(owner, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Original dictionary field unavailable: " + name); }
    }

    static Object invoke(Object owner, String name, Class<?> parameter, Object value) {
        try { return owner.getClass().getMethod(name, parameter).invoke(owner, value); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("Original dictionary method failed: " + name);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Original dictionary method unavailable: " + name); }
    }

    static void load(Object owner, ByteBuffer bytes, int version, Object... lists) {
        Class<?>[] signature = lists.length == 0 ? new Class<?>[]{ByteBuffer.class, int.class}
            : new Class<?>[]{ByteBuffer.class, int.class, List.class, List.class};
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod("load", signature); method.setAccessible(true);
                if (lists.length == 0) method.invoke(owner, bytes, version);
                else method.invoke(owner, bytes, version, lists[0], lists[1]);
                return;
            } catch (NoSuchMethodException ignored) { }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException failure) throw failure;
                throw new IllegalStateException("Original dictionary decode failed");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Original dictionary decoder unavailable"); }
        }
        throw new IllegalStateException("Original dictionary decoder unavailable");
    }

    @SuppressWarnings("unchecked")
    static List<String> overrides(Object info) { return (List<String>) get(info, "modOverrides"); }
}
