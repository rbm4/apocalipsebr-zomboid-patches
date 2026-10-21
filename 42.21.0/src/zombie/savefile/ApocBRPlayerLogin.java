package zombie.savefile;

import java.util.Map;
import java.util.WeakHashMap;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/** Request-driven getters and native movement, called by trusted server Lua. No login callback. */
public final class ApocBRPlayerLogin {
    private static final String LAST_SERVER = "ApocBR.LastConnectedServerId.v1";
    private static final Map<IsoPlayer,Context> LOGINS = new WeakHashMap<>();
    private static final class Context {
        final int previous, current;
        boolean teleported;
        Context(int previous,int current){this.previous=previous;this.current=current;}
    }
    private ApocBRPlayerLogin() {}
    private static boolean gameThread() {
        return GameServer.server && Thread.currentThread()==GameServer.mainThread;
    }
    // Capture lazily when the server's Lua login command first queries this player.
    private static void capture(IsoPlayer player) {
        if(!gameThread())throw new IllegalStateException("Login context requires the server game thread");
        if(LOGINS.containsKey(player))return;
        int current=ApocBRPayloadProvenance.configuredServerId();
        Object saved=player.getModData().rawget(LAST_SERVER);
        int previous=0;
        if(saved instanceof Number value) {
            double id=value.doubleValue();
            if(Double.isFinite(id)&&id>=1&&id<=Integer.MAX_VALUE&&id==Math.floor(id))previous=(int)id;
        }
        LOGINS.put(player,new Context(previous,current));
        // Existing relational mod-data snapshots persist this through the normal worker.
        player.getModData().rawset(LAST_SERVER,(double)current);
    }
    public static int getPreviousServerId(IsoPlayer player) {
        if(!gameThread()||player==null)return 0;
        capture(player);
        Context context=LOGINS.get(player);
        return context==null?0:context.previous;
    }
    public static boolean hasChangedServer(IsoPlayer player) {
        if(!gameThread()||player==null)return false;
        capture(player);
        Context context=LOGINS.get(player);
        return context!=null&&context.previous>0&&context.previous!=context.current;
    }
    /** Server-initiated native packet, including vanilla movement/anti-cheat resets.
     * Repeated login requests acknowledge the accepted teleport without sending it again. */
    public static boolean teleportToLobby(IsoPlayer player,double x,double y,double z) {
        if(!gameThread()||player==null||player.isDead()||!Double.isFinite(x)||!Double.isFinite(y)
                ||!Double.isFinite(z)||x<0||y<0||x>1_000_000||y>1_000_000||z< -32||z>31
                ||GameServer.getConnectionFromPlayer(player)==null||player.getNetworkCharacterAI()==null)return false;
        Context context=LOGINS.computeIfAbsent(player,p->new Context(0,0));
        if(!context.teleported) {
            GameServer.sendTeleport(player,(float)x,(float)y,(float)z);
            context.teleported=true;
        }
        return true;
    }
}
