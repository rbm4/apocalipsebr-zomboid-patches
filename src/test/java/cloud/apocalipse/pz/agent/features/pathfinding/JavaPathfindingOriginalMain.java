package cloud.apocalipse.pz.agent.features.pathfinding;

import java.nio.file.Path;
import zombie.debug.DebugOptions;
import zombie.iso.IsoMetaGrid;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.pathfind.nativeCode.PathfindNativeThread;

public final class JavaPathfindingOriginalMain {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        boolean enabled = Boolean.parseBoolean(args[1]);
        for (String target : new String[]{"zombie.pathfind.nativeCode.PathfindNative", "zombie.iso.IsoWorld",
                "zombie.network.ServerMap"}) {
            var type = Class.forName(target, false, ClassLoader.getSystemClassLoader());
            Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(source.equals(original), target + " came from " + source);
            // Force verification of the transformed method bodies without booting a world.
            type.getDeclaredMethods();
        }

        require(PathfindNative.useNativeCode == !enabled, "Unexpected initial backend");
        var option = DebugOptions.instance.pathfindUseNativeCode;
        option.setValue(true);
        PathfindNative.instance.checkUseNativeCode();
        require(PathfindNative.useNativeCode == !enabled, "Unexpected backend after requesting native");
        require(PathfindNativeThread.instance == null, "Started a native world worker");

        if (enabled) {
            option.setValue(false);
            PathfindNative.instance.checkUseNativeCode();
            option.setValue(true);
            PathfindNative.instance.checkUseNativeCode();
            require(!PathfindNative.useNativeCode && option.getValue(), "Debug changes bypassed the lock");

            var grid = new IsoMetaGrid();
            grid.minX = grid.maxX = grid.minY = grid.maxY = 0;
            var threadField = PolygonalMap2.class.getDeclaredField("thread");
            threadField.setAccessible(true);
            PolygonalMap2.instance.init(grid);
            Thread worker = (Thread) threadField.get(PolygonalMap2.instance);
            try {
                require(worker != null && worker.isAlive(), "Java worker failed to start");
            } finally {
                JavaPathfindingHooks.stopJavaWorker();
            }
            require(!worker.isAlive() && threadField.get(PolygonalMap2.instance) == null, "Java worker leaked");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
