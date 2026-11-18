package zombie.network;

import java.util.ArrayList;
import java.util.HashMap;

/** Structural double for the packaged-agent server-loop transform; never packaged. */
public final class GameServer {
    public static final ArrayList<Object> Players = new ArrayList<>();
    public static final HashMap<Short, Object> IDToPlayerMap = new HashMap<>();
    public static boolean done;

    public static void main(String[] args) {
        int remaining = 5;
        while (!done && remaining-- > 0) {
            long startServerCycle = System.nanoTime();
            if ((remaining & 1) == 0) {
                zombie.worldMap.WorldMapVisitedServer.getInstance().update();
                zombie.ApocBRServerTelemetryLite.recordTick(System.nanoTime() - startServerCycle);
            }
            zombie.ApocBRServerTelemetryLite.recordLoop(System.nanoTime() - startServerCycle);
        }
    }
}
