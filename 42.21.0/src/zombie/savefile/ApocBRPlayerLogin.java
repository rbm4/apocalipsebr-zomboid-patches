package zombie.savefile;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Successful-login audit result and native movement. Getters never access JDBC or mod-data. */
public final class ApocBRPlayerLogin {
    private static final Map<IsoPlayer,Context> LOGINS = new WeakHashMap<>();
    private static final Map<UUID,WeakReference<Context>> WAITING = new ConcurrentHashMap<>();
    private static final class Context {
        final int current;
        volatile Integer previous;
        boolean teleported;
        Context(int current){this.current=current;}
    }
    private ApocBRPlayerLogin() {}
    private static boolean gameThread() {
        return GameServer.server && Thread.currentThread()==GameServer.mainThread;
    }
    public static boolean enabled() {
        return GameServer.server&&Boolean.getBoolean("apocbr.postgres.enabled")
            &&Boolean.parseBoolean(System.getProperty("apocbr.postgres.loginHistory.enabled","true"));
    }
    /** Called once after GameServer accepts a real player, for either storage mode. */
    public static void successfulLogin(IsoPlayer player,String world,String identity,boolean coop,int slot,String username,String steam) {
        if(!gameThread()||player==null||LOGINS.containsKey(player))return;
        Context context=new Context(0);
        if(!enabled()){context.previous=0;LOGINS.put(player,context);return;}
        UUID event=UUID.randomUUID();
        try {
            int current=ApocBRPayloadProvenance.configuredServerId();
            context=new Context(current);LOGINS.put(player,context);
            WAITING.entrySet().removeIf(e->e.getValue().get()==null);
            if(WAITING.size()<512)WAITING.put(event,new WeakReference<>(context));
            else DebugLog.log("[ApocBR][LoginHistory] RESULT_CAPACITY; event="+event+"; auditStillQueued=true");
            ApocBRPersistence.submit(new ApocBRSuccessfulLogin.Login(event,System.currentTimeMillis(),current,world,identity,coop,
                slot,username,steam,ApocBRPlayerRelationalMutations.loginCharacterId(player)));
            DebugLog.log("[ApocBR][LoginHistory] QUEUED; event="+event+"; currentServer="+current+"; slot="+slot);
        }catch(RuntimeException|LinkageError failure) {
            WAITING.remove(event);
            // Successful gameplay acceptance remains successful; never invent an origin.
            LOGINS.put(player,context);
            DebugLog.log("[ApocBR][LoginHistory] QUEUE_FAILED; event="+event+"; issue="+failure.getClass().getSimpleName());
        }
    }
    /** Journal worker publishes only detached state; it never touches an IsoPlayer. */
    static void recorded(UUID event,int previous) {
        WeakReference<Context> reference=WAITING.remove(event);
        Context context=reference==null?null:reference.get();
        if(context!=null)context.previous=previous;
        DebugLog.log("[ApocBR][LoginHistory] RECORDED; event="+event+"; previousServer="+previous
            +"; currentServer="+(context==null?"offline":context.current)
            +"; changedServer="+(context!=null&&previous>0&&previous!=context.current));
    }
    public static boolean isReady(IsoPlayer player) {
        if(!gameThread()||player==null)return false;
        if(!enabled())return true;
        Context context=LOGINS.get(player);
        return context!=null&&context.previous!=null;
    }
    public static int getPreviousServerId(IsoPlayer player) {
        if(!gameThread()||player==null)return 0;
        Context context=LOGINS.get(player);
        return context==null||context.previous==null?0:context.previous;
    }
    public static boolean hasChangedServer(IsoPlayer player) {
        if(!gameThread()||player==null)return false;
        Context context=LOGINS.get(player);
        return context!=null&&context.previous!=null&&context.previous>0&&context.previous!=context.current;
    }
    /** Server-initiated native packet, including vanilla movement/anti-cheat resets.
     * Repeated login requests acknowledge the accepted teleport without sending it again. */
    public static boolean teleportToLobby(IsoPlayer player,double x,double y,double z) {
        if(!gameThread()||player==null||player.isDead()||!Double.isFinite(x)||!Double.isFinite(y)
                ||!Double.isFinite(z)||x<0||y<0||x>1_000_000||y>1_000_000||z< -32||z>31
                ||GameServer.getConnectionFromPlayer(player)==null||player.getNetworkCharacterAI()==null)return false;
        Context context=LOGINS.computeIfAbsent(player,p->new Context(0));
        if(!context.teleported) {
            GameServer.sendTeleport(player,(float)x,(float)y,(float)z);
            context.teleported=true;
        }
        return true;
    }
}
