package cloud.apocalipse.pz.agent.features.pathfinding;

import zombie.pathfind.PolygonalMap2;

/** Entry points referenced from the transformed game classes. */
public final class JavaPathfindingHooks {
    private JavaPathfindingHooks() { }

    public static boolean useNativeCode() {
        return false;
    }

    public static void stopJavaWorker() {
        PolygonalMap2.instance.stop();
    }
}
