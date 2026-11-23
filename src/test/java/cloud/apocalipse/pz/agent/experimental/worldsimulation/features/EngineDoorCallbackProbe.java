package cloud.apocalipse.pz.agent.experimental.worldsimulation.features;

import java.lang.reflect.Field;
import java.util.Map;
import se.krka.kahlua.vm.JavaFunction;
import zombie.Lua.LuaManager;
import zombie.vehicles.VehiclePart;

/** Scoped replacement of the stock empty function in the real Kahlua lookup path. */
final class EngineDoorCallbackProbe implements AutoCloseable {
    private final String name;
    private final Map<String, Object> functions;
    private final Object original;
    private final JavaFunction override;
    private int calls;
    private boolean valid;

    EngineDoorCallbackProbe(String name, VehiclePart target, float timestamp) {
        this.name = name;
        functions = functionCache();
        Thread server = Thread.currentThread();
        override = (frame, count) -> {
            if (count >= 2 && frame.get(1) == target) {
                calls++;
                valid = Thread.currentThread() == server && count == 3 && frame.get(0) == target.getOwner()
                    && frame.get(2) instanceof Double elapsed && Math.abs(elapsed - 60.0) < 0.01
                    && Float.compare(target.getLastUpdated(), timestamp) == 0;
            }
            return 0;
        };
        original = functions.put(name, override);
    }

    boolean deliveredOnce() {
        return calls == 1 && valid;
    }

    @Override public void close() {
        if (original == null) functions.remove(name, override);
        else functions.replace(name, override, original);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> functionCache() {
        try {
            Field field = LuaManager.class.getDeclaredField("luaFunctionMap");
            field.setAccessible(true);
            return (Map<String, Object>) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to install the scoped Lua override probe", failure);
        }
    }
}
