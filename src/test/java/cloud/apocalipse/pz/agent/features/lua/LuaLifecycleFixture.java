package cloud.apocalipse.pz.agent.features.lua;
/** Minimal native lifecycle bodies; production advice/visitor supplies all feature behavior. */
public final class LuaLifecycleFixture {
    public static int starts,ticks;
    public static void startServer(){starts++;}
    public static void main(String[] args){zombie.network.LoginQueue.update();ticks++;}
}
