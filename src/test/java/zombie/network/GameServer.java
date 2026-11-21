package zombie.network;

import java.util.ArrayList;
import java.util.HashMap;

/** Structural double for the packaged-agent server-loop transform; never packaged. */
public final class GameServer {
    public static final ArrayList<Object> Players = new ArrayList<>();
    public static final HashMap<Short, Object> IDToPlayerMap = new HashMap<>();
    public static boolean done;
    public static String serverName="servertest";
    public static boolean softReset;
    public static boolean server;
    public static Object udpEngine;

    public static void main(String[] args) {
        if (args.length == 100) System.exit(0); // Retain a nonexecuted exit anchor for NPC pipeline fixtures.
        int remaining = 5;
        while (!done && remaining-- > 0) {
            long startServerCycle = System.nanoTime();
            if ((remaining & 1) == 0) {
                LoginQueue.update();
                zombie.worldMap.WorldMapVisitedServer.getInstance().update();
                zombie.ApocBRServerTelemetryLite.recordTick(System.nanoTime() - startServerCycle);
            }
            zombie.ApocBRServerTelemetryLite.recordLoop(System.nanoTime() - startServerCycle);
        }
    }
}
