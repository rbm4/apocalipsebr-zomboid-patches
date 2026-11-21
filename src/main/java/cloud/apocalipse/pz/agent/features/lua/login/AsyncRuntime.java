package cloud.apocalipse.pz.agent.features.lua.login;

public final class AsyncRuntime {
    private static volatile boolean enabled;
    private static volatile boolean closed;
    private AsyncRuntime() { }
    public static void initialize(boolean value){enabled=value;closed=false;}
    public static boolean enabled(){return enabled&&!closed;}
    public static synchronized void update(){if(enabled())ApocBRAsyncLogin.update();}
    public static synchronized void shutdown(){if(!enabled())return;closed=true;ApocBRAsyncLogin.shutdown();}
}
