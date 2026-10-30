package zombie.savefile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import org.postgresql.util.PSQLException;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/** One-shot local SQLite backfill. JDBC owns detached data; native decoding owns the server thread. */
public final class ApocBRPlayerMigration {
    private static final String FLAG="apocbr.postgres.playerMigration.enabled";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final long WEEK=TimeUnit.DAYS.toMillis(7);
    private static final Map<UdpConnection,String> LOGIN_ATTEMPTS=new IdentityHashMap<>(); // server thread only
    private static volatile ApocBRPlayerMigration instance;
    private static boolean attempted;
    private final String world,save;
    private final String mode;
    private final boolean coop;
    private final ZoneId zone;
    private final Path players,accounts,logPath;
    private final UUID dataset;
    private final Thread worker;
    private volatile boolean stopping;
    private volatile Request request;
    private volatile VerificationRequest verification;
    private volatile Row reserved;
    private BufferedWriter output; // worker only
    private long selected,migrated,skipped,collisions,failed;
    private long verified,verificationFailed;

    record Row(int id,String world,String username,String steam,int slot,String name,int version,boolean dead,int bytes) {
        String identity(boolean coop){return coop?steam:username;}
    }
    private record Captured(ApocBRPlayerRelationalSnapshot.Snapshot snapshot,List<String> issues) {}
    private static final class Request {
        final Row row;final byte[] bytes;
        final CompletableFuture<Captured> result=new CompletableFuture<>();
        Request(Row row,byte[] bytes){this.row=row;this.bytes=bytes;}
    }
    private static final class VerificationRequest {
        final Row row;final ApocBRPlayerRelationalReader.Profile profile;
        final List<String> passed=new ArrayList<>();
        final List<String> diagnostics=new ArrayList<>();
        final CompletableFuture<ApocBRPlayerMigrationVerification.Restored> result=new CompletableFuture<>();
        volatile String stage="WAIT_SERVER_THREAD";
        VerificationRequest(Row row,ApocBRPlayerRelationalReader.Profile profile){this.row=row;this.profile=profile;}
    }
    private ApocBRPlayerMigration() {
        mode=System.getProperty("apocbr.postgres.playerMigration.mode","migrate");
        if(!Set.of("migrate","validate-failed").contains(mode))throw new IllegalArgumentException("Unknown player migration mode");
        world=Core.gameSaveWorld;save=ZomboidFileSystem.instance.getCurrentSaveDir();
        coop=GameServer.coop&&SteamUtils.isSteamModeEnabled();
        zone=ZoneId.systemDefault();
        players=Path.of(save,"players.db");
        accounts=Path.of(ZomboidFileSystem.instance.getCacheDir(),"db",GameServer.serverName+".db");
        logPath=Path.of(save,"player-migration.log");
        String configured=System.getProperty("apocbr.postgres.playerRelational.sourceDatasetId");
        dataset=configured==null?ApocBRPlayerRelationalDatabase.named("sqlite-save:"+save):UUID.fromString(configured);
        worker=new Thread(()->ApocBRPostgresConnector.runBackgroundDatabaseWork(this::run),"ApocBR-Player-Migration");
        worker.setDaemon(true);
    }
    public static boolean enabled(){return Boolean.getBoolean(FLAG)&&ApocBRPlayerRelationalSync.enabled();}

    /** Called even when the world is paused/empty. Never does JDBC or file I/O. */
    public static void update() {
        if(!GameServer.server||Thread.currentThread()!=GameServer.mainThread)return;
        if(!attempted) {
            attempted=true;
            if(enabled())try{instance=new ApocBRPlayerMigration();instance.worker.start();}
            catch(RuntimeException failure){DebugLog.log("[ApocBR][PlayerMigration] START_FAILED; issue="+failure.getClass().getSimpleName());}
        }
        ApocBRPlayerMigration current=instance;
        if(current==null||current.stopping){LOGIN_ATTEMPTS.clear();return;}
        LOGIN_ATTEMPTS.keySet().removeIf(c->GameServer.udpEngine==null||!GameServer.udpEngine.connections.contains(c));
        VerificationRequest check=current.verification;
        if(check!=null&&!check.result.isDone()) {
            try {
                if(current.online(check.row)){check.result.complete(null);return;}
                current.reserved=check.row;
                check.result.complete(ApocBRPlayerMigrationVerification.reconstruct(check.profile,check.row.slot(),check.row.username(),
                    stage->check.stage=stage,check.passed::add,check.diagnostics::add));
            }catch(Exception|LinkageError|AssertionError failure){check.result.completeExceptionally(failure);}
            return;
        }
        Request next=current.request;
        if(next==null||next.result.isDone())return;
        try {
            if(current.online(next.row)){next.result.complete(null);return;}
            current.reserved=next.row;
            // No synthetic connection, live tracking, audit event or destructive native load-error path.
            IsoPlayer player=new IsoPlayer(IsoWorld.instance.currentCell);
            player.serverPlayerIndex=next.row.slot();
            List<String> issues;
            try(ApocBRPlayerLoadQuality quality=ApocBRPlayerLoadQuality.begin()) {
                player.load(ByteBuffer.wrap(next.bytes),next.row.version());issues=quality.issues();
            }
            if(next.row.dead()){player.getBodyDamage().setOverallBodyHealth(0.0F);player.setHealth(0.0F);}
            player.remote=true;
            next.result.complete(new Captured(ApocBRPlayerRelationalSnapshot.copy(player),issues));
        }catch(Exception|LinkageError failure){next.result.completeExceptionally(failure);}
    }

    /** Login packet entry, before asynchronous auth has assigned connection.username. */
    public static boolean admitLogin(UdpConnection connection,String username) {
        if(!enabled())return true;
        ApocBRPlayerMigration current=instance;
        if(attempted&&(current==null||current.stopping))return true;
        Row row=current==null?null:current.reserved;
        if(row!=null&&matches(row,username,SteamUtils.convertSteamIDToString(connection.getSteamId()),current.coop)) {
            DebugLog.log("[ApocBR][PlayerMigration] LOGIN_DEFERRED; localId="+row.id()+"; retryAfterMigration=true");
            ApocBRLoginGuard.deny(connection);return false;
        }
        LOGIN_ATTEMPTS.put(connection,username);return true;
    }
    static boolean matches(Row row,String username,String steam,boolean coop) {
        return Objects.equals(row.username(),username)||coop&&Objects.equals(row.steam(),steam);
    }
    private boolean online(Row row) {
        for(var entry:LOGIN_ATTEMPTS.entrySet())if(matches(row,entry.getValue(),SteamUtils.convertSteamIDToString(entry.getKey().getSteamId()),coop))return true;
        if(GameServer.udpEngine!=null)for(UdpConnection connection:GameServer.udpEngine.connections) {
            if(matches(row,connection.getUserName(),SteamUtils.convertSteamIDToString(connection.getSteamId()),coop))return true;
            for(String user:connection.usernames)if(user!=null&&Objects.equals(row.username(),user))return true;
        }
        for(IsoPlayer player:GameServer.Players)if(Objects.equals(row.username(),player.getUsername()))return true;
        return false;
    }
    /** Null means missing, invalid, ambiguous, or future: eligible, with an explicit diagnostic. */
    static Instant lastLogin(String value,ZoneId zone,Instant now) {
        if(value==null||value.isBlank())return null;
        try {
            LocalDateTime local=LocalDateTime.parse(value,DATE);
            var offsets=zone.getRules().getValidOffsets(local);
            if(offsets.size()!=1)return null;
            Instant parsed=local.toInstant(offsets.get(0));
            return parsed.isAfter(now)?null:parsed;
        }catch(DateTimeException invalid){return null;}
    }
    static boolean recent(Instant login,Instant now){return login!=null&&!login.isBefore(now.minusMillis(WEEK));}
    private static Connection readOnly(Path path)throws SQLException,IOException {
        if(!Files.isRegularFile(path))throw new FileNotFoundException(path.getFileName().toString());
        Connection connection=DriverManager.getConnection("jdbc:sqlite:"+path.toUri()+"?mode=ro");
        try(Statement statement=connection.createStatement()){statement.execute("PRAGMA busy_timeout=5000");}
        catch(SQLException failure){connection.close();throw failure;}
        return connection;
    }
    private Row next(int after,int maximum)throws SQLException,IOException {
        try(Connection c=readOnly(players);PreparedStatement s=c.prepareStatement("SELECT id,world,username,steamid,playerIndex,name,worldversion,isDead,length(data) FROM networkPlayers WHERE id>? AND id<=? ORDER BY id LIMIT 1")) {
            s.setInt(1,after);s.setInt(2,maximum);
            try(ResultSet r=s.executeQuery()){return r.next()?new Row(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getString(6),r.getInt(7),r.getBoolean(8),r.getInt(9)):null;}
        }
    }
    private String login(Row row)throws SQLException,IOException {
        try(Connection c=readOnly(accounts);PreparedStatement s=c.prepareStatement("SELECT lastConnection FROM whitelist WHERE username=?")) {
            s.setString(1,row.username());
            try(ResultSet r=s.executeQuery()){if(!r.next())return null;String date=r.getString(1);return r.next()?null:date;}
        }
    }
    private byte[] payload(Row row)throws SQLException,IOException {
        // Recheck length inside SQL: an online save may have changed the row since enumeration.
        try(Connection c=readOnly(players);PreparedStatement s=c.prepareStatement("SELECT data FROM networkPlayers WHERE id=? AND world=? AND username=? AND playerIndex=? AND length(data)>0 AND length(data)<=?")) {
            s.setInt(1,row.id());s.setString(2,row.world());s.setString(3,row.username());s.setInt(4,row.slot());s.setInt(5,ApocBRPlayerRelationalLimits.SOURCE_BYTES);
            try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Source row changed or exceeded budget","22000");return r.getBytes(1);}
        }
    }
    private void migrate(Row row,ApocBRPlayerRelationalDatabase database)throws Exception {
        event("SELECTED",row,"");
        if(!Objects.equals(world,row.world())||row.slot()<0||row.slot()>3||row.username()==null||row.username().isBlank()||row.identity(coop)==null||row.identity(coop).isBlank()) {
            skipped++;event("DENIED_INVALID_IDENTITY_OR_WORLD",row,"");return;
        }
        Instant now=Instant.now(),last=null;
        try{last=lastLogin(login(row),zone,now);}
        catch(SQLException|IOException unavailable){event("LAST_LOGIN_UNAVAILABLE",row,"issue="+unavailable.getClass().getSimpleName());}
        if(recent(last,now)){skipped++;event("SKIPPED_RECENT_LOGIN",row,"lastLogin="+last);return;}
        if(last==null)event("LAST_LOGIN_UNKNOWN",row,"eligible=true");
        var candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,save,world,row.identity(coop),coop,row.slot(),row.id(),row.version(),null,List.of());
        var receipt=database.inspect(candidate);
        if(receipt.complete()){skipped++;event("ALREADY_MIGRATED",row,"");verify(row,receipt.character(),receipt.head());return;}
        if(row.bytes()<1||row.bytes()>ApocBRPlayerRelationalLimits.SOURCE_BYTES)throw new SQLException("Source size budget","22000");
        event("LOAD_REQUESTED",row,"");
        Request capture=new Request(row,payload(row));request=capture;
        Captured detached=capture.result.get(); // shutdown interrupts; no JDBC/SQLite transaction held while waiting
        request=null;
        if(detached==null){skipped++;event("SKIPPED_ONLINE_OR_CONNECTING",row,"");return;}
        event("LOADED",row,"");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(capture.bytes));
        candidate=new ApocBRPlayerRelationalDatabase.Candidate(dataset,save,world,row.identity(coop),coop,row.slot(),row.id(),row.version(),hash,detached.issues());
        // Reinspect after capture; another server may have published a head in the meantime.
        receipt=database.inspect(candidate);
        if(receipt.complete()){skipped++;event("ALREADY_MIGRATED",row,"");verify(row,receipt.character(),receipt.head());return;}
        var graph=new ApocBRPlayerRelationalCapture(receipt.character(),receipt.aliases(),Long.MAX_VALUE).capture(detached.snapshot(),detached.issues());
        ApocBRPlayerRelationalCapture.requireComplete(graph,"offline_migration");
        event("PERSISTING",row,"items="+graph.items()+"; rows="+graph.rows().size());
        if(stopping)throw new InterruptedException();
        if(!database.persist(candidate,receipt,graph)) {
            // Resolve a competing server's completed insert as a collision, rather than a generic stale capture.
            database.inspect(candidate);
            throw new SQLException("Slot changed during migration","40001");
        }
        migrated++;event("PERSISTED",row,"character="+receipt.character());
        verify(row,receipt.character(),graph.snapshot());
    }
    private void verify(Row row,UUID character,UUID snapshot)throws IOException,InterruptedException {
        String ids="character="+character+"; snapshot="+snapshot;
        event("VERIFICATION_STARTED",row,ids);
        String stage="READ_PROFILE";VerificationRequest check=null;
        try {
            var profile=new ApocBRPlayerRelationalReader().load(world,row.identity(coop),row.slot(),coop);
            stage="PROFILE_IDENTITY";
            // Validation pins the logged snapshot, independent of this installation's SQLite dataset path.
            ApocBRPlayerMigrationVerification.identity(profile,character,snapshot,mode.equals("validate-failed")?null:dataset);
            event("ASSERTION_PASSED",row,ids+"; check=PROFILE_IDENTITY");
            check=new VerificationRequest(row,profile);verification=check;
            ApocBRPlayerMigrationVerification.Restored restored;
            try{restored=check.result.get();}
            finally {
                // CompletableFuture completion publishes these server-thread assertions, including before an exception.
                if(check.result.isDone()) {
                    for(String diagnostic:check.diagnostics)event("RECONSTRUCTION_DIAGNOSTIC",row,ids+"; "+diagnostic);
                    for(String passed:check.passed)event("ASSERTION_PASSED",row,ids+"; check="+passed);
                }
            }
            if(restored==null){skipped++;event("VERIFICATION_SKIPPED_ONLINE",row,ids);return;}
            stage="RECAPTURE_RECONSTRUCTED_STATE";
            var graph=new ApocBRPlayerRelationalCapture(character,Map.of(),Long.MAX_VALUE).capture(restored.snapshot(),List.of());
            ApocBRPlayerRelationalCapture.requireComplete(graph,"migration_verification");
            stage="COMPARE_PERSISTED_STATE";
            List<String> passed=new ArrayList<>();
            try{ApocBRPlayerMigrationVerification.compare(profile,graph,restored.skippedTraits(),passed::add);}
            finally{for(String assertion:passed)event("ASSERTION_PASSED",row,ids+"; check="+assertion);}
            verified++;event("VERIFIED",row,ids+"; payloadBytes="+restored.payloadBytes());
        }catch(InterruptedException interrupted){throw interrupted;}
        catch(IOException auditFailure){throw auditFailure;}
        catch(Exception|LinkageError|AssertionError failure) {
            verificationFailed++;
            Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;
            if(failure instanceof ExecutionException&&check!=null)stage=check.stage;
            event("VERIFICATION_FAILED",row,ids+"; stage="+stage+"; "+verificationDiagnostic(cause)+"; persistedSnapshotRetained=true");
        }finally{verification=null;}
    }
    static String verificationDiagnostic(Throwable failure) {
        StringBuilder detail=new StringBuilder();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int depth=0;failure!=null&&depth<4&&seen.add(failure);depth++,failure=failure.getCause()) {
            if(depth>0)detail.append("; causedBy=");else detail.append("exception=");
            detail.append(failure.getClass().getName());
            // Only our typed assertions supply identifiers; arbitrary loader messages are never logged.
            if(failure instanceof ApocBRPlayerMigrationVerification.AssertionFailure assertion)detail.append("; assertion=").append(assertion.code).append(assertion.diagnostic);
            if(failure instanceof ApocBRPlayerRelationalCapture.IncompleteGraphException graph)detail.append("; ").append(graph.diagnostic());
            if(failure instanceof SQLException sql)detail.append(sqlDiagnostic(sql));
            detail.append("; frames=");var frames=failure.getStackTrace();
            for(int i=0;i<Math.min(6,frames.length);i++){if(i>0)detail.append('|');detail.append(frames[i].getClassName()).append('.').append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());}
        }
        return detail.toString();
    }
    static String sqlDiagnostic(SQLException failure) {
        StringBuilder detail=new StringBuilder();
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending=new ArrayDeque<>();pending.add(failure);int count=0;
        // JDBC batches often keep the actual server error in nextException, not cause.
        while(!pending.isEmpty()&&count<8) {
            Throwable next=pending.removeFirst();if(!seen.add(next))continue;
            detail.append("; sqlIndex=").append(count++).append("; sqlException=").append(next.getClass().getSimpleName());
            if(next instanceof SQLException sql) {
                detail.append("; SQLState=").append(ApocBRPlayerReconstructionLog.token(sql.getSQLState())).append("; errorCode=").append(sql.getErrorCode());
                if(sql instanceof PSQLException pg&&pg.getServerErrorMessage()!=null) {
                    var error=pg.getServerErrorMessage();
                    detail.append("; schema=").append(ApocBRPlayerReconstructionLog.token(error.getSchema()))
                        .append("; table=").append(ApocBRPlayerReconstructionLog.token(error.getTable()))
                        .append("; column=").append(ApocBRPlayerReconstructionLog.token(error.getColumn()))
                        .append("; constraint=").append(ApocBRPlayerReconstructionLog.token(error.getConstraint()));
                }
                if(sql.getNextException()!=null)pending.addLast(sql.getNextException());
            }
            if(next.getCause()!=null)pending.addLast(next.getCause());
        }
        // Never include driver messages, SQL, DETAIL, WHERE or duplicate key values.
        if(!pending.isEmpty())detail.append("; sqlChainTruncated=true");
        return detail.toString();
    }
    private void run() {
        try {
            output=Files.newBufferedWriter(logPath,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            event("START",null,"server="+clean(GameServer.serverName)+"; world="+clean(world)+"; dataset="+dataset+"; loginTimezone="+zone+"; intervalMs=1000; recentDays=7");
            event("EXECUTION_MODE",null,"mode="+mode);
            if(mode.equals("validate-failed")){validateFailed();return;}
            int maximum;
            try(Connection c=readOnly(players);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COALESCE(MAX(id),0) FROM networkPlayers")){r.next();maximum=r.getInt(1);}
            var database=new ApocBRPlayerRelationalDatabase(ApocBRPostgresConnector::borrowPlayerConnection);
            ApocBRPlayerRelationalCapture.prepare();
            int after=0;Row row;
            while(!stopping&&(row=next(after,maximum))!=null) {
                after=row.id();selected++;
                try{migrate(row,database);}
                catch(InterruptedException interrupted){throw interrupted;}
                catch(Exception|LinkageError failure) {
                    Throwable cause=failure instanceof ExecutionException?failure.getCause():failure;
                    // Only the existing explicit ownership denial is a collision; other 22023 errors are failures.
                    if(cause instanceof SQLException sql&&"22023".equals(sql.getSQLState())&&"Player slot belongs to another source dataset".equals(sql.getMessage())) {
                        collisions++;event("COLLISION_DISCARDED",row,"existingCharacterPreserved=true");
                    }else {
                        failed++;
                        String detail=cause instanceof ApocBRPlayerRelationalCapture.IncompleteGraphException graph?"; "+graph.diagnostic()
                            :cause instanceof ApocBRPlayerRelationalLimits.SizeExceeded size?"; "+size.diagnostic():"";
                        event("FAILED",row,"issue="+cause.getClass().getSimpleName()+"; world="+clean(world)
                            +"; identityMode="+(coop?"steam_coop":"username")+"; sourceDataset="+dataset
                            +"; "+verificationDiagnostic(cause)+detail);
                    }
                }finally{request=null;verification=null;reserved=null;}
                Thread.sleep(1000); // interval starts after the previous character's terminal outcome
            }
            event("DRAINED",null,summary());
        }catch(InterruptedException interrupted){safeEvent("STOPPED",summary());Thread.currentThread().interrupt();}
        catch(Exception|LinkageError failure){safeEvent("ABORTED",summary()+"; issue="+failure.getClass().getSimpleName());}
        finally {
            stopping=true;request=null;verification=null;reserved=null;
            if(output!=null)try{output.close();}catch(IOException failure){DebugLog.log("[ApocBR][PlayerMigration] LOG_CLOSE_FAILED");}
        }
    }
    private void validateFailed()throws IOException,InterruptedException {
        var reader=new ApocBRPlayerRelationalReader();
        ApocBRPlayerRelationalCapture.prepare();
        for(var target:ApocBRPlayerValidationTargets.failedOctober26()) {
            if(stopping)throw new InterruptedException();
            selected++;
            String ids="character="+target.character()+"; snapshot="+target.snapshot()+"; localId="+target.localId();
            try {
                event("VALIDATION_TARGET",null,ids);
                var slot=reader.validationSlot(world,target.character());
                if(slot==null){skipped++;event("VALIDATION_TARGET_NOT_ACTIVE",null,ids);continue;}
                if(slot.coop()!=coop||slot.slot()<0||slot.slot()>3||slot.identity()==null||slot.identity().isBlank()) {
                    skipped++;event("VALIDATION_TARGET_IDENTITY_DENIED",null,ids);continue;
                }
                String username=slot.coop()?slot.username():slot.identity();
                if(username==null||username.isBlank()){skipped++;event("VALIDATION_TARGET_IDENTITY_DENIED",null,ids);continue;}
                Row row=new Row(target.localId(),world,username,slot.coop()?slot.identity():"",slot.slot(),"<validation>",0,false,0);
                verify(row,target.character(),target.snapshot());
            }catch(InterruptedException interrupted){throw interrupted;}
            catch(IOException auditFailure){throw auditFailure;}
            catch(Exception|LinkageError failure){failed++;event("VALIDATION_TARGET_FAILED",null,ids+"; "+verificationDiagnostic(failure));}
            finally{verification=null;reserved=null;if(!stopping)Thread.sleep(1000);}
        }
        event("DRAINED",null,summary());
    }
    private String summary(){return "selected="+selected+"; migrated="+migrated+"; skipped="+skipped+"; collisions="+collisions+"; failed="+failed+"; verified="+verified+"; verificationFailed="+verificationFailed;}
    private void event(String code,Row row,String details)throws IOException {
        String message=Instant.now()+"; "+code+(row==null?"":"; localId="+row.id()+"; username="+clean(row.username())+"; characterName="+clean(row.name())+"; slot="+row.slot())+"; "+details;
        // Flush before continuing: a broken audit file stops the migration.
        output.write(message);output.newLine();output.flush();
        DebugLog.log("[ApocBR][PlayerMigration] "+message);
    }
    private static String clean(String text){return text==null?"<null>":text.replaceAll("[\\p{Cntrl};]","_");}
    private void safeEvent(String code,String details){try{if(output!=null)event(code,null,details);else DebugLog.log("[ApocBR][PlayerMigration] "+code+"; "+details);}catch(IOException failure){DebugLog.log("[ApocBR][PlayerMigration] LOG_FAILED; migrationStopped=true");}}
    public static void shutdown() {
        ApocBRPlayerMigration current=instance;if(current==null)return;
        current.stopping=true;current.worker.interrupt();
        boolean interrupted=false;
        while(current.worker.isAlive())try{current.worker.join(1000);}catch(InterruptedException stopped){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
