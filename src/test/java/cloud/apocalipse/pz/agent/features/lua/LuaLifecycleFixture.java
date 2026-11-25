package cloud.apocalipse.pz.agent.features.lua;

import zombie.network.LoginQueue;
import zombie.network.ServerMap;

/** Minimal native lifecycle bodies; production advice/visitor supplies all feature behavior. */
public final class LuaLifecycleFixture {
    public static int starts,ticks;
    private static ServerMap serverMap;

    public static void startServer(){starts++;}

    public static void main(String[] args) {
        LoginQueue.update();
        // Include the native preupdate anchor without starting world simulation in this fixture.
        if (serverMap != null) {
            serverMap.preupdate();
        }
        ticks++;
    }
}
