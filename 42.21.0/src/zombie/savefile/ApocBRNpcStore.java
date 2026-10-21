package zombie.savefile;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import zombie.apocalipse.humans.*;
import zombie.apocalipse.humans.profiles.*;
import zombie.iso.IsoDirections;
import zombie.debug.DebugLog;

/** Detached NPC persistence boundary. The worker never reads/mutates a live game object. */
public final class ApocBRNpcStore {
    public record State(ServerHumanDefinition definition,HumanLifecycle.State lifecycle,int activations,
                        int profileRevision,float x,float y,int z,long revision,boolean retired) {
        public State {
            Objects.requireNonNull(definition);Objects.requireNonNull(lifecycle);
            if(activations<0||profileRevision<0||revision<1||!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=Integer.MAX_VALUE||y>=Integer.MAX_VALUE||z< -32||z>31)
                throw new IllegalArgumentException("Invalid NPC snapshot");
        }
    }
    public record Restored(State state,boolean activate) { }
    public record DialogueIdentity(String npc,String dialogue,String kind,String account) {
        public DialogueIdentity {
            if(npc==null||npc.isBlank()||npc.length()>128||dialogue==null||!dialogue.matches("[A-Za-z0-9_.-]{1,128}")
                ||!("steam".equals(kind)||"username".equals(kind))||account==null||account.isBlank()||account.length()>128)
                throw new IllegalArgumentException("Invalid dialogue identity");
        }
    }
    private record EarnedFlag(DialogueIdentity identity,String flag) { }
    public record Loaded(List<HumanProfile> profiles,List<Restored> instances) {
        public Loaded {profiles=List.copyOf(profiles);instances=List.copyOf(instances);}
    }
    private static volatile ApocBRNpcStore instance;
    private final String server,world;
    private final ServerHumanDefinition bootstrap;
    private final ConcurrentHashMap<String,State> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DialogueIdentity,Set<String>> dialogueFlags=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EarnedFlag,Boolean> pendingFlags=new ConcurrentHashMap<>();
    private int flagCount;
    private final AtomicReference<Loaded> loaded=new AtomicReference<>();
    private final AtomicReference<List<HumanProfile>> profiles=new AtomicReference<>();
    private final Thread worker;
    private volatile boolean stopping;
    private volatile boolean healthy;
    private Connection connection;
    private boolean locked;
    private long epoch;
    private volatile boolean initialized;
    private String lastIssue="";
    public static boolean enabled(){return Boolean.getBoolean("apocbr.npc.persistence.enabled");}
    public static ApocBRNpcStore current(){return instance;}
    public Set<String> dialogueFlags(DialogueIdentity identity){return dialogueFlags.getOrDefault(identity,Set.of());}
    /** Capture detached facts only; JDBC and journal writes remain on the worker. */
    public synchronized boolean remember(DialogueIdentity identity,String flag) {
        if(!initialized||!healthy||stopping||flag==null||!flag.matches("[A-Za-z0-9_]{1,48}"))return false;
        var previous=dialogueFlags(identity);if(previous.contains(flag))return true;
        if(previous.size()>=128||flagCount>=100000)return false;
        var next=new HashSet<>(previous);next.add(flag);
        pendingFlags.put(new EarnedFlag(identity,flag),true);
        dialogueFlags.put(identity,Set.copyOf(next));flagCount++;return true;
    }
    public static synchronized ApocBRNpcStore start(String server,String world,ServerHumanDefinition bootstrap) {
        if(!enabled()||!ApocBRPersistence.enabled())throw new IllegalStateException("NPC persistence requires the PostgreSQL journal");
        if(instance!=null&&(!instance.server.equals(server)||!instance.world.equals(world)))throw new IllegalStateException("NPC store already owns a different scope");
        if(instance==null)instance=new ApocBRNpcStore(server,world,bootstrap);
        return instance;
    }
    private ApocBRNpcStore(String server,String world,ServerHumanDefinition bootstrap) {
        if(server==null||server.isBlank()||server.length()>128||world==null||world.isBlank()||world.length()>128)throw new IllegalArgumentException("Invalid NPC scope");
        this.server=server;this.world=world;this.bootstrap=bootstrap;
        worker=new Thread(this::run,"ApocBR-NPC-Store");worker.setDaemon(true);worker.start();
    }
    public Loaded takeLoaded(){return loaded.getAndSet(null);}
    public List<HumanProfile> takeProfiles(){return profiles.getAndSet(null);}
    public boolean healthy(){return healthy;}
    public void offer(State state) {
        if(stopping)throw new IllegalStateException("NPC persistence is stopping");
        if(!pending.containsKey(state.definition().key())&&pending.size()>=256)throw new IllegalStateException("NPC checkpoint capacity exceeded");
        pending.compute(state.definition().key(),(key,old)->old==null||old.revision()<state.revision()?state:old);
    }
    private void run() {
        ApocBRPostgresConnector.runBackgroundDatabaseWork(()->{
            long nextProfiles=0;
            while(!stopping) {
                try {
                    if(!ApocBRPersistence.reachable()) {healthy=false;Thread.sleep(1000);continue;}
                    if(connection==null)connect();
                    if(!initialized) {
                        var definitions=readInstances();var configuration=readProfiles(connection);
                        var dialogues=ApocBRNpcDialogues.read(connection);
                        readDialogueFlags();
                        connection.commit();zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(dialogues);
                        loaded.set(new Loaded(configuration,definitions));initialized=true;nextProfiles=System.nanoTime()+30000000000L;
                    }
                    // Session lock + epoch fence prevent two processes writing the same NPC scope.
                    try(var ping=statement(connection,"SELECT epoch FROM npc_engine.scope WHERE server_key=? AND world=?",server,world);var rows=ping.executeQuery()) {
                        if(!rows.next()||rows.getLong(1)!=epoch)throw new SQLException("NPC scope ownership lost","55000");
                    }
                    connection.commit();healthy=true;
                    flush();
                    if(System.nanoTime()>=nextProfiles){
                        var configuration=readProfiles(connection);var dialogues=ApocBRNpcDialogues.read(connection);
                        connection.commit();zombie.apocalipse.humans.dialogue.HumanDialogueCatalog.publish(dialogues);
                        profiles.set(configuration);nextProfiles=System.nanoTime()+30000000000L;
                    }
                    lastIssue="";Thread.sleep(1000);
                } catch(InterruptedException interrupted){if(!stopping)Thread.currentThread().interrupt();break;}
                catch(SQLException|RuntimeException failure) {
                    healthy=false;
                    String issue=failure instanceof SQLException sql?"SQLSTATE="+sql.getSQLState():failure.getClass().getSimpleName();
                    if(!issue.equals(lastIssue)){DebugLog.log("[ApocBR][NPCStore] worker unavailable "+issue);lastIssue=issue;}
                    closeConnection();
                    // Reconnect only under the same epoch. A superseding owner requires a restart/reload.
                    if(initialized&&failure instanceof SQLException sql&&"55000".equals(sql.getSQLState()))break;
                    try{Thread.sleep(5000);}catch(InterruptedException interrupted){break;}
                }
            }
            healthy=false;
            // Even after lease loss, journal only old-epoch snapshots; the SQL fence makes them harmless.
            if(initialized)try{flush();}catch(RuntimeException failure){DebugLog.log("[ApocBR][NPCStore] final checkpoint not staged: "+failure.getClass().getSimpleName());}
            closeConnection();
        });
    }
    private void connect()throws SQLException {
        connection=ApocBRPostgresConnector.borrowServerConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try(var s=statement(connection,"SELECT pg_try_advisory_lock(hashtext(?),hashtext(?))","npc-engine:"+server,world);var r=s.executeQuery()){
            if(!r.next()||!r.getBoolean(1))throw new SQLException("NPC scope already owned","55000");locked=true;
        }
        if(initialized) {
            try(var s=statement(connection,"SELECT epoch FROM npc_engine.scope WHERE server_key=? AND world=?",server,world);var r=s.executeQuery()) {
                if(!r.next()||r.getLong(1)!=epoch)throw new SQLException("NPC ownership superseded","55000");
            }
        } else {
            try(var s=statement(connection,"INSERT INTO npc_engine.scope(server_key,world,epoch) VALUES(?,?,1) ON CONFLICT(server_key,world) DO UPDATE SET epoch=npc_engine.scope.epoch+1 RETURNING epoch",server,world);var r=s.executeQuery()){r.next();epoch=r.getLong(1);}
            if(bootstrap!=null)insertDefinition(connection,server,world,bootstrap,true);
        }
        connection.commit();
    }
    private void closeConnection() {
        if(connection==null)return;
        try{connection.rollback();if(locked)try(var s=statement(connection,"SELECT pg_advisory_unlock(hashtext(?),hashtext(?))","npc-engine:"+server,world)){s.execute();}connection.commit();}catch(SQLException ignored){}
        try{connection.close();}catch(SQLException ignored){}connection=null;locked=false;
    }
    private void flush() {
        for(var flag:pendingFlags.keySet()) {
            var id=flag.identity();
            var sql=new ApocBRPersistenceOperations.Sql("""
                INSERT INTO npc_engine.dialogue_flag(server_key,world,npc_key,dialogue_id,identity_kind,player_identity,flag)
                SELECT ?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
                ON CONFLICT DO NOTHING
                """,List.of(server,world,id.npc(),id.dialogue(),id.kind(),id.account(),flag.flag(),server,world,epoch));
            ApocBRPersistence.submit(new ApocBRPersistenceOperations.Account(List.of(sql)));
            pendingFlags.remove(flag);
        }
        for(var entry:pending.entrySet()) {
            State state=entry.getValue();
            ApocBRPersistence.submit(new ApocBRPersistenceOperations.Account(statements(server,world,epoch,state)));
            pending.remove(entry.getKey(),state);
        }
    }
    private void readDialogueFlags()throws SQLException {
        Map<DialogueIdentity,Set<String>> loadedFlags=new HashMap<>();int count=0;
        try(var s=statement(connection,"SELECT npc_key,dialogue_id,identity_kind,player_identity,flag FROM npc_engine.dialogue_flag WHERE server_key=? AND world=? LIMIT 100001",server,world);var r=s.executeQuery()) {
            while(r.next()) {
                if(++count>100000)throw new SQLException("Dialogue flag cache capacity exceeded","22023");
                var id=new DialogueIdentity(r.getString(1),r.getString(2),r.getString(3),r.getString(4));
                var flags=loadedFlags.computeIfAbsent(id,key->new HashSet<>());flags.add(r.getString(5));
                if(flags.size()>128)throw new SQLException("Account dialogue flag capacity exceeded","22023");
            }
        }
        dialogueFlags.clear();loadedFlags.forEach((id,flags)->dialogueFlags.put(id,Set.copyOf(flags)));flagCount=count;
    }
    static List<ApocBRPersistenceOperations.Sql> statements(String server,String world,long epoch,State state) {
        var d=state.definition();
        var definition=new ApocBRPersistenceOperations.Sql("""
            INSERT INTO npc_engine.instance(server_key,world,npc_key,username,display_name,profile_id,home_x,home_y,home_z,facing,female,outfit,desired_active)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
            ON CONFLICT(server_key,world,npc_key) DO NOTHING
            """,List.of(server,world,d.key(),d.username(),d.displayName(),d.profileId(),d.x(),d.y(),d.z(),d.facing().name(),d.female(),d.outfit(),state.lifecycle()==HumanLifecycle.State.ACTIVE,server,world,epoch));
        var snapshot=new ApocBRPersistenceOperations.Sql("""
            INSERT INTO npc_engine.runtime_state(server_key,world,npc_key,epoch,mutation_revision,lifecycle,activations,profile_revision,x,y,z,retired)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
            ON CONFLICT(server_key,world,npc_key) DO UPDATE SET epoch=EXCLUDED.epoch,mutation_revision=EXCLUDED.mutation_revision,
                lifecycle=EXCLUDED.lifecycle,activations=EXCLUDED.activations,profile_revision=EXCLUDED.profile_revision,
                x=EXCLUDED.x,y=EXCLUDED.y,z=EXCLUDED.z,retired=EXCLUDED.retired,updated_at=now()
            WHERE npc_engine.runtime_state.epoch<EXCLUDED.epoch OR (npc_engine.runtime_state.epoch=EXCLUDED.epoch AND npc_engine.runtime_state.mutation_revision<EXCLUDED.mutation_revision)
            """,List.of(server,world,d.key(),epoch,state.revision(),state.lifecycle().name(),state.activations(),state.profileRevision(),state.x(),state.y(),state.z(),state.retired(),server,world,epoch));
        return List.of(definition,snapshot);
    }
    private static void insertDefinition(Connection c,String server,String world,ServerHumanDefinition d,boolean active)throws SQLException {
        try(var s=statement(c,"""
            INSERT INTO npc_engine.instance(server_key,world,npc_key,username,display_name,profile_id,home_x,home_y,home_z,facing,female,outfit,desired_active)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(server_key,world,npc_key) DO NOTHING
            """,server,world,d.key(),d.username(),d.displayName(),d.profileId(),d.x(),d.y(),d.z(),d.facing().name(),d.female(),d.outfit(),active)){s.executeUpdate();}
    }
    private List<Restored> readInstances()throws SQLException {
        var result=new ArrayList<Restored>();
        try(var s=statement(connection,"""
            SELECT i.npc_key,i.username,i.display_name,i.home_x,i.home_y,i.home_z,i.facing,i.female,i.outfit,i.profile_id,i.desired_active,
                r.lifecycle,r.activations,r.profile_revision,r.x,r.y,r.z,r.mutation_revision,r.retired
            FROM npc_engine.instance i LEFT JOIN npc_engine.runtime_state r USING(server_key,world,npc_key)
            WHERE i.server_key=? AND i.world=? ORDER BY i.npc_key LIMIT 257
            """,server,world);var r=s.executeQuery()) {
            while(r.next()) {
                if(result.size()>=256)throw new SQLException("NPC instance limit exceeded","22023");
                var d=new ServerHumanDefinition(r.getString(1),r.getString(2),r.getString(3),r.getFloat(4),r.getFloat(5),r.getInt(6),IsoDirections.valueOf(r.getString(7)),r.getBoolean(8),r.getString(9),r.getString(10));
                String life=r.getString(12);boolean fresh=life==null;
                var state=new State(d,fresh?HumanLifecycle.State.DORMANT:HumanLifecycle.State.valueOf(life),r.getInt(13),r.getInt(14),fresh?d.x():r.getFloat(15),fresh?d.y():r.getFloat(16),fresh?d.z():r.getInt(17),Math.max(1,r.getLong(18)),r.getBoolean(19));
                result.add(new Restored(state,r.getBoolean(11)&&(fresh||state.lifecycle()==HumanLifecycle.State.ACTIVE||state.lifecycle()==HumanLifecycle.State.SPAWNING)));
            }
        }
        return List.copyOf(result);
    }
    static List<HumanProfile> readProfiles(Connection connection)throws SQLException {
        var result=new ArrayList<HumanProfile>();
        try(var s=statement(connection,"""
            SELECT p.profile_id,p.current_revision,r.dialogue_enabled,r.trading_enabled,r.greeting_key,r.announcement_provider,r.perception_radius,r.perception_interval_ms,r.require_los
            FROM npc_engine.profile p JOIN npc_engine.profile_revision r ON r.profile_id=p.profile_id AND r.revision=p.current_revision ORDER BY p.profile_id LIMIT 257
            """);var rows=s.executeQuery()) {
            while(rows.next()) {
                if(result.size()>=256)throw new SQLException("NPC profile limit exceeded","22023");
                String id=rows.getString(1);int revision=rows.getInt(2);
                var bindings=new ArrayList<HumanModuleBinding>();
                try(var m=statement(connection,"SELECT module_id,priority,cycle_ms FROM npc_engine.profile_module WHERE profile_id=? AND revision=? AND enabled ORDER BY priority,module_id LIMIT 17",id,revision);var mr=m.executeQuery()) {
                    while(mr.next()) {
                        if(bindings.size()>=16)throw new SQLException("NPC module limit exceeded","22023");
                        String module=mr.getString(1);var parameters=new HashMap<String,Long>();
                        try(var v=statement(connection,"SELECT parameter_key,value_ms FROM npc_engine.profile_parameter WHERE profile_id=? AND revision=? AND module_id=? LIMIT 17",id,revision,module);var vr=v.executeQuery()){
                            while(vr.next())parameters.put(vr.getString(1),vr.getLong(2));
                        }
                        bindings.add(new HumanModuleBinding(module,mr.getInt(2),mr.getInt(3),parameters));
                    }
                }
                result.add(new HumanProfile(id,revision,"modules",Map.of(),rows.getString(5),rows.getString(6),rows.getBoolean(3),rows.getBoolean(4),bindings,rows.getDouble(7),rows.getInt(8),rows.getBoolean(9)));
            }
        }
        return List.copyOf(result);
    }
    static PreparedStatement statement(Connection c,String sql,Object...values)throws SQLException {
        var s=c.prepareStatement(sql);try{s.setQueryTimeout(2);for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;}catch(SQLException failure){s.close();throw failure;}
    }
    /** Invoked before the shared journal drains/closes. All values were already detached on the simulation thread. */
    public static synchronized void shutdown() {
        var current=instance;if(current==null)return;
        current.stopping=true;current.worker.interrupt();
        try{current.worker.join(10000);}catch(InterruptedException failure){Thread.currentThread().interrupt();}
        if(current.worker.isAlive())throw new IllegalStateException("NPC persistence worker did not stop before journal shutdown");
        // A fail-closed runtime may still accept explicit administrative retirements.
        if(current.initialized)current.flush();
    }
}
