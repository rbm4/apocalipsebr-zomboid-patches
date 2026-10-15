package zombie.savefile;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Initial-only SQLite -> relational handoff. Native login/save results never depend on it. */
public final class ApocBRPlayerRelationalSync {
    private static final int LIMIT=16;
    private static final long BYTE_LIMIT=16L*1024*1024;
    private static final Map<String,Pending> PENDING=new ConcurrentHashMap<>();
    private static final BlockingQueue<Pending> CHECKS=new ArrayBlockingQueue<>(LIMIT);
    private static final BlockingQueue<Pending> CAPTURES=new ArrayBlockingQueue<>(LIMIT);
    private static final BlockingQueue<Work> WRITES=new ArrayBlockingQueue<>(LIMIT);
    private static final AtomicLong QUEUED_BYTES=new AtomicLong();
    private static volatile Thread worker;
    private static volatile boolean stopping;
    private static long lastDeferredLog;
    private static final ApocBRPlayerRelationalDatabase DATABASE=new ApocBRPlayerRelationalDatabase(ApocBRPostgresConnector::borrowPlayerConnection);
    private static final class Pending {
        final ApocBRPlayerRelationalDatabase.Candidate candidate;
        final WeakReference<IsoPlayer> player;
        volatile ApocBRPlayerRelationalDatabase.Receipt receipt;
        volatile long readyAt;
        final long expires=System.nanoTime()+120_000_000_000L;
        int attempts;
        Pending(ApocBRPlayerRelationalDatabase.Candidate candidate,IsoPlayer player){this.candidate=candidate;this.player=new WeakReference<>(player);}
    }
    private record Work(Pending pending,ApocBRPlayerRelationalCapture.Graph graph) {}
    public static boolean enabled() {
        return GameServer.server && !ApocBRPlayerDatabase.enabled() && Boolean.getBoolean("apocbr.postgres.enabled")
                && Boolean.parseBoolean(System.getProperty("apocbr.postgres.playerRelational.enabled","true"));
    }
    public static void offerLoadedPlayer(IsoPlayer player,int sqlId,String world,String identity,int slot,
                                         int worldVersion,byte[] sourceBytes,List<String> loadIssues) {
        if(!enabled()||stopping)return;
        try {
            String label=ZomboidFileSystem.instance.getCurrentSaveDir();
            String configured=System.getProperty("apocbr.postgres.playerRelational.sourceDatasetId");
            UUID dataset=configured==null?ApocBRPlayerRelationalDatabase.named("sqlite-save:"+label):UUID.fromString(configured);
            // Source hash is provenance only. Never use changing bytes to invent a new character life.
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sourceBytes));
            var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,label,world,identity,
                    GameServer.coop&&SteamUtils.isSteamModeEnabled(),slot,sqlId,worldVersion,hash,loadIssues);
            synchronized(PENDING) {
                if(PENDING.containsKey(candidate.key()))return;
                if(PENDING.size()>=LIMIT){deferred();return;}
                Pending pending=new Pending(candidate,player);PENDING.put(candidate.key(),pending);
                if(!CHECKS.offer(pending)){PENDING.remove(candidate.key());deferred();return;}
            }
            start();
        } catch(Exception|LinkageError error){log("OFFER_FAILED; exception="+error.getClass().getSimpleName());}
    }
    private static synchronized void start() {
        if(worker!=null||stopping)return;
        worker=new Thread(ApocBRPlayerRelationalSync::run,"ApocBR-player-relational");worker.setDaemon(true);worker.start();
        log("worker started; source=SQLite; pendingLimit="+LIMIT);
    }
    /** Called on the game thread. No JDBC and no worker waiting. One consistent one-shot graph per tick. */
    public static void updateMain() {
        if(stopping)return;
        Pending pending=CAPTURES.poll();if(pending==null)return;
        if(System.nanoTime()<pending.readyAt){CAPTURES.offer(pending);return;}
        IsoPlayer player=pending.player.get();
        if(player==null||System.nanoTime()>pending.expires){forget(pending);return;}
        if(QUEUED_BYTES.get()>BYTE_LIMIT-ApocBRPlayerRelationalCapture.MAX_BYTES){CAPTURES.offer(pending);return;}
        try {
            long budget=Math.max(1,Math.min(25,Integer.getInteger("apocbr.postgres.playerRelational.captureBudgetMs",8)))*1_000_000L;
            var graph=new ApocBRPlayerRelationalCapture(pending.receipt.character(),pending.receipt.aliases(),budget).capture(player,pending.candidate.loadIssues());
            QUEUED_BYTES.addAndGet(graph.bytes());
            if(!WRITES.offer(new Work(pending,graph))){QUEUED_BYTES.addAndGet(-graph.bytes());CAPTURES.offer(pending);}
        } catch(RuntimeException|LinkageError error){retry(pending,"CAPTURE_FAILED; code="+safeCode(error));}
    }
    private static void run() {
        while(!stopping) {
            Work work=WRITES.poll();
            if(work!=null) {
                try {
                    boolean published=DATABASE.persist(work.pending.candidate,work.pending.receipt,work.graph);
                    if(published) {log((work.graph.issues().isEmpty()?"SYNC_COMPLETE":"SYNC_INCOMPLETE")+"; items="+work.graph.items()+"; rows="+work.graph.rows().size()+"; issues="+work.graph.issues().size());forget(work.pending);}
                    else retry(work.pending,"STALE_CAPTURE");
                } catch(SQLException|RuntimeException error){retry(work.pending,"SQL_FAILURE; SQLState="+(error instanceof SQLException sql?sql.getSQLState():"LOCAL"));}
                finally{QUEUED_BYTES.addAndGet(-work.graph.bytes());}
                continue;
            }
            try {
                Pending pending=CHECKS.poll(200,TimeUnit.MILLISECONDS);if(pending==null)continue;
                if(System.nanoTime()>pending.expires){forget(pending);continue;}
                if(System.nanoTime()<pending.readyAt){CHECKS.offer(pending);Thread.sleep(50);continue;}
                try {
                    pending.receipt=DATABASE.inspect(pending.candidate);
                    if(pending.receipt.complete()){log("SYNC_SKIPPED; receipt=complete");forget(pending);}
                    else if(!CAPTURES.offer(pending)){retry(pending,"CAPTURE_QUEUE_FULL");}
                } catch(SQLException|RuntimeException error){retry(pending,"CHECK_FAILURE; SQLState="+(error instanceof SQLException sql?sql.getSQLState():"LOCAL"));}
            } catch(InterruptedException interrupted){if(stopping)return;}
        }
    }
    private static void retry(Pending pending,String code) {
        if(stopping||++pending.attempts>=3||System.nanoTime()>pending.expires){log(code+"; deferredUntilNextLoad=true");forget(pending);return;}
        pending.readyAt=System.nanoTime()+(1L<<pending.attempts)*1_000_000_000L;
        if(!CHECKS.offer(pending))forget(pending);
        log(code+"; retry="+pending.attempts);
    }
    private static String safeCode(Throwable error){
        String message=error.getMessage();
        if(message!=null&&(message.matches("[A-Z_]+")||message.matches("Missing (capture|required) [A-Za-z0-9_.$ ]+")))return message;
        return error.getClass().getSimpleName();
    }
    private static void forget(Pending pending){PENDING.remove(pending.candidate.key(),pending);}
    private static void log(String code){DebugLog.log("[ApocBR][PlayerRelational] "+code);}
    private static synchronized void deferred(){long now=System.nanoTime();if(now-lastDeferredLog>30_000_000_000L){lastDeferredLog=now;log("QUEUE_FULL; deferredUntilNextLoad=true");}}
    public static void shutdown() {
        stopping=true;Thread current=worker;
        if(current!=null){current.interrupt();try{current.join(4000);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        if(!PENDING.isEmpty())log("shutdown; pending="+PENDING.size()+"; retryOnNextLoad=true");
        CHECKS.clear();CAPTURES.clear();WRITES.clear();PENDING.clear();
    }
    private ApocBRPlayerRelationalSync() {}
}
