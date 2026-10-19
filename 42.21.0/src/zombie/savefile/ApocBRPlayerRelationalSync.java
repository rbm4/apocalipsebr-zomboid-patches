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

/** SQLite backfill and relational live-field worker. Login reads have their own caller-owned lease. */
public final class ApocBRPlayerRelationalSync {
    private static final int LIMIT=16;
    private static final long BYTE_LIMIT=ApocBRPlayerRelationalLimits.QUEUE_BYTES;
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
        final UUID session;
        volatile boolean cancelled;
        final long snapshotNanos;
        volatile UUID character;
        final int bytes;
        long readyAt;
        final long expires=System.nanoTime()+120_000_000_000L;
        int attempts;
        Pending(ApocBRPlayerRelationalDatabase.Candidate candidate,ApocBRPlayerRelationalSnapshot.Snapshot snapshot,byte[] sourceBytes,long snapshotNanos,UUID session) {
            this.candidate=candidate;this.snapshot=snapshot;this.sourceBytes=sourceBytes;
            this.snapshotNanos=snapshotNanos;this.session=session;bytes=snapshot.bytes()+sourceBytes.length;
        }
    }
    public static boolean enabled() {
        return GameServer.server && Boolean.getBoolean("apocbr.postgres.enabled")
                && Boolean.parseBoolean(System.getProperty("apocbr.postgres.playerRelational.enabled","true"));
    }
    public static void offerLoadedPlayer(IsoPlayer player,int sqlId,String world,String identity,int slot,
                                         int worldVersion,byte[] sourceBytes,List<String> loadIssues) {
        offerPlayer(player,sqlId,world,identity,slot,worldVersion,sourceBytes,loadIssues,null);
    }
    public static void offerCreatedPlayer(IsoPlayer player,String world,String identity,int slot,int worldVersion) {
        offerPlayer(player,0,world,identity,slot,worldVersion,new byte[0],List.of(),UUID.randomUUID());
    }
    /** Handoff must fail closed if the complete new-life capture was not accepted. */
    static boolean offerCreatedPlayerForHandoff(IsoPlayer player,String world,String identity,int slot,int worldVersion) {
        return offerPlayer(player,0,world,identity,slot,worldVersion,new byte[0],List.of(),UUID.randomUUID());
    }
    /** An authoritative relational load is already synced. Bind its actual life/dataset
     * immediately, rather than waiting on an import receipt based on a local SQLite path. */
    static void registerPostgresPlayer(IsoPlayer player,String world,String identity,int slot,boolean coop,
                                       ApocBRPlayerRelationalReader.Profile profile) {
        if(!enabled())return;
        if(stopping)throw new IllegalStateException("Relational worker is stopping");
        var metadata=profile.one("snapshot");
        UUID character=UUID.fromString(Objects.requireNonNull(metadata.get("persisted_character_id"),"Missing persisted character ID").toString());
        UUID dataset=UUID.fromString(Objects.requireNonNull(metadata.get("source_dataset_id"),"Missing persisted source dataset").toString());
        var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,ZomboidFileSystem.instance.getCurrentSaveDir(),world,identity,
                coop,slot,0,((Number)metadata.get("source_world_version")).intValue(),null,List.of());
        Map<Integer,UUID> identities=new HashMap<>();
        for(var item:profile.rows("item"))identities.put(((Number)item.get("native_id")).intValue(),UUID.fromString(Objects.requireNonNull(item.get("persisted_item_id"),"Missing persisted item ID").toString()));
        UUID session=ApocBRPlayerRelationalMutations.register(player,candidate);
        if(session==null)throw new IllegalStateException("Relational tracking capacity exceeded");
        ApocBRPlayerRelationalMutations.bind(candidate,session,character,identities);
        start();
        log("LIVE_TRACK_READY; source=PostgreSQL; initialSync=skipped");
    }
    private static boolean offerPlayer(IsoPlayer player,int sqlId,String world,String identity,int slot,int worldVersion,byte[] sourceBytes,List<String> loadIssues,UUID life) {
        if(!enabled()||stopping)return false;
        try {
            String label=ZomboidFileSystem.instance.getCurrentSaveDir();
            String configured=System.getProperty("apocbr.postgres.playerRelational.sourceDatasetId");
            UUID dataset=configured==null?ApocBRPlayerRelationalDatabase.named("sqlite-save:"+label):UUID.fromString(configured);
            var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,label,world,identity,
                    GameServer.coop&&SteamUtils.isSteamModeEnabled(),slot,sqlId,worldVersion,null,loadIssues,life);
            UUID session=ApocBRPlayerRelationalMutations.register(player,candidate);
            if(ApocBRPersistence.journaling()) {
                if(session==null)throw new IllegalStateException("Relational tracking capacity exceeded");
                if(sourceBytes.length>ApocBRPlayerRelationalLimits.SOURCE_BYTES)throw new IllegalStateException("SOURCE_SIZE_BUDGET");
                if(life==null&&sourceBytes.length>0) {
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sourceBytes));
                    candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,label,world,identity,candidate.coop(),slot,sqlId,worldVersion,hash,loadIssues,life);
                }
                var snapshot=life==null?ApocBRPlayerRelationalSnapshot.copy(player):ApocBRPlayerRelationalSnapshot.copyLive(player);
                ApocBRPersistence.submit(new ApocBRPersistenceOperations.Initial(candidate,session,snapshot));
                if(life!=null)ApocBRPlayerRelationalMutations.bind(candidate,session,life);
                start();return true;
            }
            synchronized(PENDING) {
                Pending existing=PENDING.get(candidate.key());
                if(existing!=null&&life!=null){existing.cancelled=true;QUEUE.remove(existing);forget(existing);existing=null;}
                if(existing!=null){if(existing.character!=null)ApocBRPlayerRelationalMutations.bind(candidate,session,existing.character);return true;}
                if(PENDING.size()>=LIMIT){deferred();return false;}
                // Reserve worst-case input capacity before reading/copying player state.
                if(sourceBytes.length>ApocBRPlayerRelationalLimits.SOURCE_BYTES){log("OFFER_FAILED; code=SOURCE_SIZE_BUDGET; sourceBytes="+sourceBytes.length+"; byteLimit="+ApocBRPlayerRelationalLimits.SOURCE_BYTES);return false;}
                if(QUEUED_BYTES.get()>BYTE_LIMIT-ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES-sourceBytes.length){deferred();return false;}
                long started=System.nanoTime();
                var snapshot=life==null?ApocBRPlayerRelationalSnapshot.copy(player):ApocBRPlayerRelationalSnapshot.copyLive(player);
                Pending pending=new Pending(candidate,snapshot,sourceBytes.clone(),System.nanoTime()-started,session);
                PENDING.put(candidate.key(),pending);QUEUED_BYTES.addAndGet(pending.bytes);
                if(!QUEUE.offer(pending)){forget(pending);deferred();return false;}
            }
            start();return true;
        } catch(Exception|LinkageError error){log("OFFER_FAILED; code="+safeCode(error));return false;}
    }
    private static synchronized void start() {
        if(worker!=null||stopping)return;
        worker=new Thread(ApocBRPlayerRelationalSync::run,"ApocBR-player-relational");worker.setDaemon(true);worker.start();
        log("worker started; source="+(ApocBRPlayerDatabase.enabled()?"relational":"SQLite")+"; pendingLimit="+LIMIT+"; graphByteLimit="+ApocBRPlayerRelationalLimits.GRAPH_BYTES+"; snapshotByteLimit="+ApocBRPlayerRelationalLimits.SNAPSHOT_BYTES+"; queueByteLimit="+BYTE_LIMIT+"; rowLimit="+ApocBRPlayerRelationalLimits.ROWS+"; nodeLimit="+ApocBRPlayerRelationalLimits.NODES);
    }
    private static void run() {
        ApocBRPlayerRelationalCapture.prepare();
        boolean liveTurn=true;
        while(!stopping) {
            try {
                if(liveTurn&&ApocBRPlayerRelationalMutations.flushOne(DATABASE)){liveTurn=false;continue;}
                liveTurn=true;
                Pending pending=QUEUE.poll();
                if(pending==null){if(ApocBRPlayerRelationalMutations.flushOne(DATABASE))continue;pending=QUEUE.poll(50,TimeUnit.MILLISECONDS);if(pending==null)continue;}
                if(pending.cancelled||System.nanoTime()>pending.expires){forget(pending);continue;}
                if(System.nanoTime()<pending.readyAt){QUEUE.offer(pending);if(!ApocBRPlayerRelationalMutations.flushOne(DATABASE))Thread.sleep(10);continue;}
                String phase="CHECK_FAILURE";
                try {
                    var receipt=DATABASE.inspect(pending.candidate);
                    pending.character=pending.candidate.newLife()==null?receipt.character():pending.candidate.newLife();
                    ApocBRPlayerRelationalMutations.bind(pending.candidate,pending.session,pending.character,receipt.aliases());
                    if(pending.cancelled){forget(pending);continue;}
                    if(receipt.complete()){log("SYNC_SKIPPED; receipt=complete; snapshotMs="+TimeUnit.NANOSECONDS.toMillis(pending.snapshotNanos));forget(pending);continue;}
                    if(pending.candidate.blobHash()==null&&pending.candidate.newLife()==null) {
                        // Provenance only; changing source bytes never invent a new character life.
                        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pending.sourceBytes));
                        var c=pending.candidate;
                        pending.candidate=new ApocBRPlayerRelationalDatabase.Candidate(c.dataset(),c.label(),c.world(),c.identity(),c.coop(),c.slot(),c.nativeId(),c.worldVersion(),hash,c.loadIssues(),c.newLife());
                    }
                    phase="CAPTURE_FAILED";
                    long started=System.nanoTime();
                    var graph=new ApocBRPlayerRelationalCapture(pending.character,receipt.aliases(),Long.MAX_VALUE)
                            .capture(pending.snapshot,pending.candidate.loadIssues());
                    long elapsed=System.nanoTime()-started;
                    log("CAPTURE_COMPLETE; snapshotMs="+TimeUnit.NANOSECONDS.toMillis(pending.snapshotNanos)
                            +"; projectionMs="+TimeUnit.NANOSECONDS.toMillis(elapsed)+"; items="+graph.items()
                            +"; rows="+graph.rows().size()+"; bytes="+graph.bytes());
                    phase="SQL_FAILURE";
                    long writeStarted=System.nanoTime();
                    if(pending.cancelled){forget(pending);continue;}
                    if(DATABASE.persist(pending.candidate,receipt,graph)) {
                        log((graph.issues().isEmpty()?"SYNC_COMPLETE":"SYNC_INCOMPLETE")+"; items="+graph.items()+"; rows="+graph.rows().size()+"; issues="+graph.issues().size()+"; writeMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-writeStarted));
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
        if(error instanceof ApocBRPlayerRelationalLimits.SizeExceeded size)return size.diagnostic();
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
        boolean interrupted=false;
        if(current!=null){current.interrupt();while(current.isAlive())try{current.join(1000);}catch(InterruptedException error){interrupted=true;}}
        if(ApocBRPersistence.journaling()) {
            // Initial replay binds SQLite-backfill sessions before their final scalar batches.
            ApocBRPersistence.drain();
            // Every detached batch goes to the journal before tracking state can be cleared.
            ApocBRPlayerRelationalMutations.journalPending();
            ApocBRPersistence.drain();
        }
        if(!PENDING.isEmpty())log("shutdown; pending="+PENDING.size()+"; retryOnNextLoad=true");
        QUEUE.clear();PENDING.clear();QUEUED_BYTES.set(0);
        ApocBRPlayerRelationalMutations.shutdown();
        if(interrupted)Thread.currentThread().interrupt();
    }
    private ApocBRPlayerRelationalSync() {}
}
