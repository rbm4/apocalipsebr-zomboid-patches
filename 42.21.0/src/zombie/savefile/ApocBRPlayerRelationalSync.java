package zombie.savefile;

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
    private static final BlockingQueue<Pending> QUEUE=new ArrayBlockingQueue<>(LIMIT);
    private static final AtomicLong QUEUED_BYTES=new AtomicLong();
    private static volatile Thread worker;
    private static volatile boolean stopping;
    private static long lastDeferredLog;
    private static final ApocBRPlayerRelationalDatabase DATABASE=new ApocBRPlayerRelationalDatabase(ApocBRPostgresConnector::borrowPlayerConnection);
    private static final class Pending {
        ApocBRPlayerRelationalDatabase.Candidate candidate;
        final ApocBRPlayerRelationalSnapshot.Snapshot snapshot;
        final byte[] sourceBytes;
        final long snapshotNanos;
        final int bytes;
        long readyAt;
        final long expires=System.nanoTime()+120_000_000_000L;
        int attempts;
        Pending(ApocBRPlayerRelationalDatabase.Candidate candidate,ApocBRPlayerRelationalSnapshot.Snapshot snapshot,byte[] sourceBytes,long snapshotNanos) {
            this.candidate=candidate;this.snapshot=snapshot;this.sourceBytes=sourceBytes;
            this.snapshotNanos=snapshotNanos;bytes=snapshot.bytes()+sourceBytes.length;
        }
    }
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
            var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,label,world,identity,
                    GameServer.coop&&SteamUtils.isSteamModeEnabled(),slot,sqlId,worldVersion,null,loadIssues);
            synchronized(PENDING) {
                if(PENDING.containsKey(candidate.key()))return;
                if(PENDING.size()>=LIMIT){deferred();return;}
                // Reserve worst-case input capacity before reading/copying player state.
                if(sourceBytes.length>ApocBRPlayerRelationalCapture.MAX_BYTES
                        ||QUEUED_BYTES.get()>BYTE_LIMIT-ApocBRPlayerRelationalCapture.MAX_BYTES-sourceBytes.length){deferred();return;}
                long started=System.nanoTime();
                var snapshot=ApocBRPlayerRelationalSnapshot.copy(player);
                Pending pending=new Pending(candidate,snapshot,sourceBytes.clone(),System.nanoTime()-started);
                PENDING.put(candidate.key(),pending);QUEUED_BYTES.addAndGet(pending.bytes);
                if(!QUEUE.offer(pending)){forget(pending);deferred();return;}
            }
            start();
        } catch(Exception|LinkageError error){log("OFFER_FAILED; code="+safeCode(error));}
    }
    private static synchronized void start() {
        if(worker!=null||stopping)return;
        worker=new Thread(ApocBRPlayerRelationalSync::run,"ApocBR-player-relational");worker.setDaemon(true);worker.start();
        log("worker started; source=SQLite; pendingLimit="+LIMIT);
    }
    private static void run() {
        ApocBRPlayerRelationalCapture.prepare();
        while(!stopping) {
            try {
                Pending pending=QUEUE.poll(200,TimeUnit.MILLISECONDS);if(pending==null)continue;
                if(System.nanoTime()>pending.expires){forget(pending);continue;}
                if(System.nanoTime()<pending.readyAt){QUEUE.offer(pending);Thread.sleep(50);continue;}
                String phase="CHECK_FAILURE";
                try {
                    var receipt=DATABASE.inspect(pending.candidate);
                    if(receipt.complete()){log("SYNC_SKIPPED; receipt=complete; snapshotMs="+TimeUnit.NANOSECONDS.toMillis(pending.snapshotNanos));forget(pending);continue;}
                    if(pending.candidate.blobHash()==null) {
                        // Provenance only; changing source bytes never invent a new character life.
                        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pending.sourceBytes));
                        var c=pending.candidate;
                        pending.candidate=new ApocBRPlayerRelationalDatabase.Candidate(c.dataset(),c.label(),c.world(),c.identity(),c.coop(),c.slot(),c.nativeId(),c.worldVersion(),hash,c.loadIssues());
                    }
                    phase="CAPTURE_FAILED";
                    long started=System.nanoTime();
                    var graph=new ApocBRPlayerRelationalCapture(receipt.character(),receipt.aliases(),Long.MAX_VALUE)
                            .capture(pending.snapshot,pending.candidate.loadIssues());
                    long elapsed=System.nanoTime()-started;
                    log("CAPTURE_COMPLETE; snapshotMs="+TimeUnit.NANOSECONDS.toMillis(pending.snapshotNanos)
                            +"; projectionMs="+TimeUnit.NANOSECONDS.toMillis(elapsed)+"; items="+graph.items()
                            +"; rows="+graph.rows().size()+"; bytes="+graph.bytes());
                    phase="SQL_FAILURE";
                    if(DATABASE.persist(pending.candidate,receipt,graph)) {
                        log((graph.issues().isEmpty()?"SYNC_COMPLETE":"SYNC_INCOMPLETE")+"; items="+graph.items()+"; rows="+graph.rows().size()+"; issues="+graph.issues().size());
                        forget(pending);
                    } else retry(pending,"STALE_CAPTURE");
                } catch(SQLException error){retry(pending,phase+"; SQLState="+error.getSQLState());}
                catch(Exception|LinkageError error){retry(pending,phase+"; code="+safeCode(error));}
            } catch(InterruptedException interrupted){if(stopping)return;}
        }
    }
    private static void retry(Pending pending,String code) {
        if(stopping||++pending.attempts>=3||System.nanoTime()>pending.expires){log(code+"; deferredUntilNextLoad=true");forget(pending);return;}
        pending.readyAt=System.nanoTime()+(1L<<pending.attempts)*1_000_000_000L;
        if(!QUEUE.offer(pending))forget(pending);
        log(code+"; retry="+pending.attempts);
    }
    private static String safeCode(Throwable error){
        String message=error.getMessage();
        if(message!=null&&(message.matches("[A-Z_]+")||message.matches("Missing (capture|required|snapshot) [A-Za-z0-9_.$ ]+")))return message;
        return error.getClass().getSimpleName();
    }
    private static void forget(Pending pending){
        if(PENDING.remove(pending.candidate.key(),pending))QUEUED_BYTES.addAndGet(-pending.bytes);
    }
    private static void log(String code){DebugLog.log("[ApocBR][PlayerRelational] "+code);}
    private static synchronized void deferred(){long now=System.nanoTime();if(now-lastDeferredLog>30_000_000_000L){lastDeferredLog=now;log("QUEUE_FULL; deferredUntilNextLoad=true");}}
    public static void shutdown() {
        stopping=true;Thread current=worker;
        if(current!=null){current.interrupt();try{current.join(4000);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        if(!PENDING.isEmpty())log("shutdown; pending="+PENDING.size()+"; retryOnNextLoad=true");
        QUEUE.clear();PENDING.clear();QUEUED_BYTES.set(0);
    }
    private ApocBRPlayerRelationalSync() {}
}
