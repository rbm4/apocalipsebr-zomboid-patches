package cloud.apocalipse.pz.agent.features.telemetry;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/** Reads the current-cell zombie gauge without linking the agent to game classes. */
final class TelemetryWorldSnapshot {
    private TelemetryWorldSnapshot() {
    }

    static String gameVersion() {
        try {
            Class<?> coreType = Class.forName("zombie.core.Core");
            Object core = coreType.getMethod("getInstance").invoke(null);
            if (core == null) {
                throw new IllegalStateException("Core.getInstance returned null");
            }

            Object gameVersion = coreType.getMethod("getVersionNumber").invoke(core);
            if (!(gameVersion instanceof String version) || version.isBlank()) {
                throw new IllegalStateException("Core.getVersionNumber returned an invalid version");
            }

            return version;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read the game version from Core", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Core version lookup failed", failure.getCause());
        }
    }

    static int onlinePlayers() {
        try {
            Class<?> serverType = Class.forName("zombie.network.GameServer");
            Object players = serverType.getField("Players").get(null);
            Object playersById = serverType.getField("IDToPlayerMap").get(null);
            if (!(players instanceof Collection<?> playerCollection)
                    || !(playersById instanceof Map<?, ?> playerMap)) {
                throw new IllegalStateException("GameServer player registries have unexpected types");
            }

            return Math.max(playerCollection.size(), playerMap.size());
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException("Unable to read the 42.22.2 online-player gauge", failure);
        }
    }

    static Integer activeZombieCount() {
        try {
            Class<?> worldType = Class.forName("zombie.iso.IsoWorld");
            Field instanceField = worldType.getField("instance");
            Object world = instanceField.get(null);
            if (world == null) {
                return null;
            }

            Field currentCellField = worldType.getField("currentCell");
            Object currentCell = currentCellField.get(world);
            if (currentCell == null) {
                return null;
            }

            Method getZombieList = currentCell.getClass().getMethod("getZombieList");
            Object zombies = getZombieList.invoke(currentCell);
            if (!(zombies instanceof Collection<?> collection)) {
                throw new IllegalStateException("IsoCell.getZombieList did not return a collection");
            }

            return collection.size();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | NoSuchMethodException failure) {
            throw new IllegalStateException("Unable to read the 42.22.2 active-zombie gauge", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("IsoCell.getZombieList failed during telemetry capture", failure.getCause());
        }
    }

    static Map<String, Object> capture() {
        Map<String, Object> world = new LinkedHashMap<>();
        Integer zombies = activeZombieCount();
        if (zombies != null) world.put("zombies", zombies);
        try {
            Class<?> mapType = Class.forName("zombie.network.ServerMap");
            Object map = mapType.getField("instance").get(null);
            if (map != null) {
                world.put("loadedCells", ((Collection<?>) mapType.getField("loadedCells").get(map)).size());
                Field pending = mapType.getDeclaredField("toLoad");
                if (pending.trySetAccessible()) world.put("pendingCells", ((Collection<?>) pending.get(map)).size());
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Map gauges are optional on an incompatible runtime.
        }
        try {
            Class<?> worldType = Class.forName("zombie.iso.IsoWorld");
            Object instance = worldType.getField("instance").get(null);
            Object cell = instance == null ? null : worldType.getField("currentCell").get(instance);
            if (cell != null) {
                Collection<?> objects = (Collection<?>) cell.getClass().getMethod("getObjectList").invoke(cell);
                Map<String, Integer> classes = new TreeMap<>();
                for (Object object : objects) {
                    if (object != null) classes.merge(object.getClass().getName(), 1, Integer::sum);
                }
                world.put("movingObjects", objects.size());
                world.put("movingObjectsByClass", classes);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Loaded-object gauges are optional on an incompatible runtime.
        }
        return world;
    }
}
