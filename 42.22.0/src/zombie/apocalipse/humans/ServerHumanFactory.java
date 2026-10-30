package zombie.apocalipse.humans;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.text.Normalizer;
import zombie.apocalipse.humans.state.*;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

/**
 * Factory and lifecycle owner for server-owned human replicas. Experimental async
 * mode runs native NPC simulation on one worker, concurrently with the live world.
 */
public final class ServerHumanFactory {
    // Stock 42.22 clients require PlayerHit envelope IDs below 1024.
    // GameServer excludes the matching whole connection slots from login allocation.
    // Do not recycle IDs: the 256-ID process budget is separate from 64 active actors.
    static final int FIRST_ID = ServerHumanPresentationIds.FIRST_ID;
    static final int LAST_ID = ServerHumanPresentationIds.LAST_ID;
    static final int MAX_ACTIVE = 64;
    static final long UPDATE_NANOS = 500_000_000L;
    static final long BRAIN_UPDATE_NANOS = 100_000_000L;
    static final boolean ASYNC = Boolean.parseBoolean(System.getProperty("apocbr.npc.async", "true"));
    static final long CHECKPOINT_NANOS = Math.max(1000,Math.min(60000,
        Long.getLong("apocbr.npc.persistence.checkpointMs",30000L)))*1_000_000L;
    private static final ServerHumanFactory INSTANCE = new ServerHumanFactory();
    private final Map<String, HumanLifecycle> instances = new LinkedHashMap<>();
    private final Map<String, ServerHuman> humans = new LinkedHashMap<>();
    private final Map<Short, ServerHuman> byId = new HashMap<>();
    private final ServerHumanZombieVictims zombieVictims = new ServerHumanZombieVictims();
    private final zombie.apocalipse.humans.traversal.TraversalWorld traversalWorld = new zombie.apocalipse.humans.traversal.TraversalWorld();
    private final Set<String> reservedNames = new HashSet<>();
    private volatile Thread owner;
    private volatile ServerHumanSimulationWorker worker;
    private volatile boolean stopping;
    private boolean configured;
    private zombie.savefile.ApocBRNpcStore store;
    private final Map<String,zombie.savefile.ApocBRNpcStore.State> checkpoints=new HashMap<>();
    private long nextCheckpoint;
    private boolean persistencePaused;
    private boolean configurationRejected;
    private final Set<String> retiredKeys=new HashSet<>();
    private record Respawn(String key,long at,String deathId) { }
    private final java.util.PriorityQueue<Respawn> respawns=new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Respawn::at));
    public synchronized void reputationPort(zombie.apocalipse.humans.death.HumanReputationPort port){requireOwner();ServerHumanDeaths.reputation=java.util.Objects.requireNonNull(port);}
    private int nextId = FIRST_ID;
    private long nextUpdate;
    private long nextBrainUpdate;
    private record PendingMutation(String key,int activation,long revision,HumanMutation mutation,
                                   java.util.concurrent.CompletableFuture<HumanStateStore.Result> result) { }
    private final java.util.concurrent.ArrayBlockingQueue<PendingMutation> mutations=new java.util.concurrent.ArrayBlockingQueue<>(1024);
    private volatile boolean acceptMutations;

    private ServerHumanFactory() {
    }

    public static ServerHumanFactory getInstance() {
        return INSTANCE;
    }

    public record SimulationStatus(boolean asynchronous,boolean started,boolean failed,long ticks,long lastDurationNanos,long overruns) { }
    /** Lock-free diagnostics; counters describe the last completed worker tick. */
    public static SimulationStatus simulationStatus() {
        var running=INSTANCE.worker;
        return new SimulationStatus(ASYNC,running!=null,running!=null&&running.failed(),
            running==null?0:running.ticks(),running==null?0:running.lastDurationNanos(),running==null?0:running.overruns());
    }

    /** Called by GameServer once per active tick, after world initialization. */
    public static void update() {
        if (INSTANCE.stopping) return;
        if (!GameServer.server || GameServer.udpEngine == null
            || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        // Bootstrap/configuration and Lua API installation remain on the calling server thread.
        if (INSTANCE.worker == null) {
            INSTANCE.tick(System.nanoTime());
            if (ASYNC && INSTANCE.configured && !INSTANCE.configurationRejected) INSTANCE.startWorker();
        } else ServerHumanDialogue.install();
        zombie.apocalipse.zombies.ServerZombieController.getInstance().install(INSTANCE.zombieVictims);
    }

    private synchronized void startWorker() {
        if (worker != null || stopping) return;
        requireOwner();
        worker = new ServerHumanSimulationWorker(BRAIN_UPDATE_NANOS, this::tick, this::workerFailed);
        DebugLog.log("[ApocBR][NPC] simulation=async; targetHz=10; thread=ApocBR-NPC-Simulation; live-world experiment");
        worker.start();
    }

    private synchronized void workerFailed(Throwable failure) {
        acceptMutations=false;
        rejectPending(HumanStateStore.Status.UNAVAILABLE);
        DebugLog.log("[ApocBR][NPC] async simulation stopped after failure; restart required; ticks="+worker.ticks()
            +"; lastDurationNanos="+worker.lastDurationNanos()+"; overruns="+worker.overruns());
        failure.printStackTrace();
    }

    public static void shutdown() {
        INSTANCE.stopping=true;
        var running=INSTANCE.worker;
        if(running!=null)running.shutdown();
        synchronized(INSTANCE) {
            INSTANCE.acceptMutations=false;
            INSTANCE.rejectPending(HumanStateStore.Status.UNAVAILABLE);
            for(String key:INSTANCE.instances.keySet())INSTANCE.checkpoint(key,false);
        }
    }

    synchronized void tick(long now) {
        if(stopping)return;
        if (owner == null) {
            owner = Thread.currentThread(); ServerHumanPlayerAttacks.ready();
            if(!ASYNC)DebugLog.log("[ApocBR][NPC] simulation=sync; targetHz=10; thread="+owner.getName());
        }
        requireOwner();
        if(worker==null||!worker.isWorkerThread())ServerHumanDialogue.install();
        if(configurationRejected)return;
        if(!configured) {
            if(zombie.savefile.ApocBRNpcStore.enabled()) {
                if(store==null)try {
                    store=zombie.savefile.ApocBRNpcStore.start(System.getProperty("apocbr.npc.serverKey",GameServer.serverName),
                        zombie.core.Core.gameSaveWorld,ServerHumanDefinition.configuredGuide());
                } catch(RuntimeException failure) {
                    configurationRejected=true;
                    DebugLog.log("[ApocBR][ServerHumans] NPC persistence configuration rejected: "+failure.getClass().getSimpleName());return;
                }
                var loaded=store.takeLoaded();
                if(loaded==null)return;
                try {
                    ServerHumanProfiles.registry().install(loaded.profiles(),true);
                    Set<String> names=new HashSet<>();
                    long activeCount=loaded.instances().stream().filter(r->!r.state().retired()&&r.activate()&&r.state().lifecycle()!=HumanLifecycle.State.DEAD).count();
                    if(activeCount>MAX_ACTIVE)throw new IllegalArgumentException("Too many active NPCs");
                    for(var restored:loaded.instances()) {
                        var state=restored.state();if(state.retired())continue;
                        ServerHumanProfiles.registry().require(state.definition().profileId());checkOnlineName(state.definition());
                        if(!names.add(canonicalName(state.definition().username())))throw new IllegalArgumentException("Conflicting NPC names");
                    }
                } catch(RuntimeException failure) {
                    configurationRejected=true;
                    DebugLog.log("[ApocBR][ServerHumans] NPC startup configuration rejected: "+failure.getClass().getSimpleName()+"; repair configuration and restart");return;
                }
                configured=true;
                for(var restored:loaded.instances()) {
                    var state=restored.state();if(state.retired()){retiredKeys.add(state.definition().key());reservedNames.add(canonicalName(state.definition().username()));continue;}
                    registerDormant(state.definition());
                    instances.get(state.definition().key()).restore(state.lifecycle(),state.activations(),state.profileRevision());
                    instances.get(state.definition().key()).physiology().restore(state.physiology());
                    instances.get(state.definition().key()).restoreDeath(state.death());
                    checkpoints.put(state.definition().key(),state);
                    if(restored.activate()&&state.lifecycle()==HumanLifecycle.State.DEAD)scheduleRespawn(state.definition().key());
                    if(restored.activate()&&state.lifecycle()!=HumanLifecycle.State.DEAD)try{activate(state.definition().key());}
                        catch(RuntimeException failure){DebugLog.log("[ApocBR][ServerHumans] NPC activation deferred key="+state.definition().key()+" issue="+failure.getClass().getSimpleName());}
                }
                DebugLog.log("[ApocBR][ServerHumans] PostgreSQL NPC state loaded instances="+instances.size());
            } else {
                configured=true;
                try {var guide=ServerHumanDefinition.configuredGuide();if(guide!=null)spawn(guide);}
                catch(RuntimeException failure){DebugLog.log("[ApocBR][ServerHumans] Guide startup failed: "+failure);}
            }
        }
        if(store!=null) {
            var profiles=store.takeProfiles();
            if(profiles!=null)try{ServerHumanProfiles.registry().install(profiles,false);}
                catch(RuntimeException failure){DebugLog.log("[ApocBR][ServerHumans] Rejected database profile batch: "+failure.getClass().getSimpleName());}
            if(!store.healthy()) {
                acceptMutations=false;
                rejectPending(HumanStateStore.Status.UNAVAILABLE);
                if(!persistencePaused)for(var human:humans.values()) {
                    ServerHumanDialogue.close(human,"persistence_unavailable");ServerHumanTrading.close(human);
                    human.activity.setBlocked(true,now,human.actuator);
                    human.speech.cancel();
                    for(var viewer:human.viewers)ServerHumanTransport.idle(human,viewer);
                }
                persistencePaused=true;return;
            }
            if(persistencePaused)for(var human:humans.values())human.activity.setBlocked(false,now,human.actuator);
            persistencePaused=false;
        }
        if (now < nextBrainUpdate) return;
        nextBrainUpdate = now + BRAIN_UPDATE_NANOS;
        respawnDue(System.currentTimeMillis());
        acceptMutations=true;
        for(int count=0;count<128;count++){
            var command=mutations.poll();if(command==null)break;
            command.result().complete(mutate(command.key(),command.activation(),command.revision(),command.mutation()));
        }
        boolean heartbeat = now >= nextUpdate;
        if (heartbeat) nextUpdate = now + UPDATE_NANOS;
        java.util.List<String> dead = null;
        for (ServerHuman human : humans.values()) {
            ServerHumanMovement.Presentation previousMotion = human.movement.presentation();
            if(human.player.isDead())human.state.dispatch(new HumanMutation.Kill());
            human.physiology.update(now);
            var inputs=human.movement.inputs();
            human.movement.derivedConditions(HumanConditionCalculator.movement(human.state.snapshot(),inputs.context(),inputs.effects()));
            if (human.state.snapshot().dead()) {
                if (dead == null) dead = new java.util.ArrayList<>();
                dead.add(human.definition.key());
                continue;
            }
            human.perception.update(now);
            // Forget disconnected/replaced connection instances; do not retain them forever.
            Iterator<UdpConnection> iterator = human.viewers.iterator();
            while (iterator.hasNext()) {
                UdpConnection connection = iterator.next();
                if (!GameServer.udpEngine.connections.contains(connection) || !connection.isFullyConnected()) {
                    iterator.remove();
                    human.movementPresentation.remove(connection);
                    human.equipmentPresentation.remove(connection);
                }
            }
            for (UdpConnection connection : GameServer.udpEngine.connections) {
                if (!human.isRelevant(connection)) continue;
                if (!human.viewers.contains(connection)) ServerHumanTransport.announce(human, connection);
            }
            float previousDirection = human.player.getDirectionAngleRadians();
            ServerHumanTrading.update(human, now);
            ServerHumanDialogue.update(human, now);
            human.activity.update(now, human.actuator::observe, human.actuator,human.state.snapshot());
            human.speech.update(now);
            human.actuator.updateCorpseDrag(now);
            human.movement.refresh(now);
            // Simulate at 10 Hz, but publish routine motion only on the 2 Hz heartbeat.
            // Turns and gait/speed/animation changes replace stale client prediction immediately.
            boolean turned = Float.compare(previousDirection, human.player.getDirectionAngleRadians()) != 0;
            if (heartbeat || turned || !previousMotion.equals(human.movement.presentation())) {
                for (UdpConnection connection : human.viewers) {
                    if (human.isRelevant(connection)) ServerHumanTransport.idle(human, connection);
                }
            }
        }
        if (dead != null) for (String key : dead) markDead(key);
        if(store!=null&&now>=nextCheckpoint){nextCheckpoint=now+CHECKPOINT_NANOS;for(String key:instances.keySet())checkpoint(key,false);}
    }

    /** Register logical state independently of a live presentation. Simulation thread only. */
    public synchronized void registerDormant(ServerHumanDefinition definition) {
        requireOwner();
        ServerHumanProfiles.registry().require(definition.profileId());
        if(instances.size()+retiredKeys.size()>=256)throw new IllegalStateException("NPC logical instance limit reached");
        if(retiredKeys.contains(definition.key()))throw new IllegalArgumentException("NPC key permanently retired");
        if (instances.containsKey(definition.key())) throw new IllegalArgumentException("Duplicate human key: " + definition.key());
        String name = canonicalName(definition.username());
        if (reservedNames.contains(name)) throw new IllegalArgumentException("Human username already used in this server process");
        checkOnlineName(definition);
        instances.put(definition.key(),new HumanLifecycle(definition,this::requireOwner));
        reservedNames.add(name);
    }

    /** Compatibility spawn creates a logical instance and activates it immediately. */
    public synchronized short spawn(ServerHumanDefinition definition) {
        registerDormant(definition);
        return activate(definition.key());
    }

    public synchronized short activate(String key) {
        return activate(key,false,System.currentTimeMillis());
    }
    private short activate(String key,boolean respawn,long epochMs) {
        requireOwner();
        if(humans.size()>=MAX_ACTIVE)throw new IllegalStateException("Active NPC limit reached");
        HumanLifecycle instance = instances.get(key);
        if (instance == null) throw new IllegalArgumentException("Unknown human key: " + key);
        ServerHumanDefinition definition = instance.definition();
        ServerHumanProfiles.registry().require(definition.profileId());
        checkOnlineName(definition);
        if(respawn)instance.respawning(epochMs);else instance.spawning();
        try {
            short id = allocateId();
            if(instance.physiology().snapshot().dead())throw new IllegalStateException("Dead NPC physical state cannot activate");
            ServerHuman human = new ServerHuman(definition,id,IsoWorld.instance.currentCell,instance.physiology(),traversalWorld);
            var restored=checkpoints.get(key);
            if(restored!=null&&!respawn){human.player.setX(restored.x());human.player.setY(restored.y());human.player.setZ(restored.z());human.player.realx=restored.x();human.player.realy=restored.y();human.player.realz=(byte)restored.z();human.postX=restored.x();human.postY=restored.y();}
            humans.put(key,human); byId.put(id,human); instance.active(human.profile.revision());
            checkpoint(key,false);
            DebugLog.log("[ApocBR][ServerHumans] Spawned key=" + key + " profile=" + definition.profileId()
                + " id=" + id + " at=" + definition.x() + "," + definition.y() + "," + definition.z());
            ServerHumanDialogue.rosterChanged();
            return id;
        } catch (RuntimeException failure) {
            if (instance.state() == HumanLifecycle.State.SPAWNING) instance.spawnFailed();
            throw failure;
        }
    }

    private void checkOnlineName(ServerHumanDefinition definition) {
        String name = canonicalName(definition.username());
        for (zombie.characters.IsoPlayer player : GameServer.IDToPlayerMap.values()) {
            if (player.getUsername() != null && canonicalName(player.getUsername()).equals(name))
                throw new IllegalArgumentException("Human username conflicts with an online player");
        }
    }

    public synchronized HumanLifecycle.Snapshot lifecycle(String key) {
        requireOwner();
        HumanLifecycle instance = instances.get(key);
        return instance == null ? null : instance.snapshot();
    }

    public synchronized boolean deactivate(String key) { return retire(key,false); }
    public synchronized boolean markDead(String key) { return retire(key,true); }
    private boolean retire(String key, boolean death) {
        requireOwner();
        ServerHuman human = humans.remove(key);
        if (human == null) return false;
        byId.remove(human.player.getOnlineID());
        if (death) {instances.get(key).physiology().dispatch(new HumanMutation.Kill());instances.get(key).dead();}
        else instances.get(key).dormant();
        checkpoint(key,false,human);
        if(death){
            scheduleRespawn(key);
            try{ServerHumanDeaths.died(human,instances.get(key));}
            catch(RuntimeException failure){HumanLog.log(HumanLog.Level.ERROR,()->"NPC death effects failed key="+key+"; issue="+failure.getClass().getSimpleName());}
        }
        human.dispose();
        ServerHumanDialogue.rosterChanged();
        return true;
    }

    /** Permanent administrative removal; dormancy uses deactivate instead. */
    public synchronized boolean remove(String key) {
        requireOwner();
        if (!instances.containsKey(key)) return false;
        deactivate(key);
        checkpoint(key,true);
        instances.remove(key);retiredKeys.add(key);
        return true;
    }

    private void checkpoint(String key,boolean retired){checkpoint(key,retired,humans.get(key));}
    private void checkpoint(String key,boolean retired,ServerHuman human) {
        if(store==null)return;
        var logical=instances.get(key);var d=logical.definition();var old=checkpoints.get(key);
        float x=human!=null?human.player.getX():old!=null?old.x():d.x();
        float y=human!=null?human.player.getY():old!=null?old.y():d.y();
        int z=human!=null?human.player.getZi():old!=null?old.z():d.z();
        long revision=old==null?1:old.revision();
        var state=new zombie.savefile.ApocBRNpcStore.State(d,logical.state(),logical.activations(),logical.snapshot().profileRevision(),x,y,z,revision,retired,logical.physiology().snapshot(),logical.death());
        if(old!=null&&old.equals(state))return;
        state=new zombie.savefile.ApocBRNpcStore.State(d,state.lifecycle(),state.activations(),state.profileRevision(),x,y,z,revision+1,retired,state.physiology(),state.death());
        store.offer(state);checkpoints.put(key,state);
        HumanLog.checkpoint(old,state);
    }

    private void scheduleRespawn(String key){
        var logical=instances.get(key);if(logical.definition().deathPolicy().enabled()&&logical.death().respawnAtMs()>0)
            respawns.add(new Respawn(key,logical.death().respawnAtMs(),logical.death().deathId()));
    }
    private void respawnDue(long now){
        for(int budget=0;budget<8&&!respawns.isEmpty()&&respawns.peek().at()<=now;budget++){
            var ticket=respawns.remove();var logical=instances.get(ticket.key());
            if(logical==null||logical.state()!=HumanLifecycle.State.DEAD||!logical.death().deathId().equals(ticket.deathId())||!logical.definition().deathPolicy().enabled())continue;
            var d=logical.definition();
            if(humans.size()>=MAX_ACTIVE||IsoWorld.instance.currentCell.getGridSquare((int)Math.floor(d.x()),(int)Math.floor(d.y()),d.z())==null){
                respawns.add(new Respawn(ticket.key(),now+1000,ticket.deathId()));continue;
            }
            try{activate(ticket.key(),true,now);}
            catch(RuntimeException failure){
                respawns.add(new Respawn(ticket.key(),now+1000,ticket.deathId()));
                HumanLog.log(HumanLog.Level.ERROR,()->"NPC respawn deferred key="+ticket.key()+"; issue="+failure.getClass().getSimpleName());
            }
        }
    }

    /** Fallback for vanilla clients discovering/reloading an unknown remote-player ID. */
    public static boolean handleDataRequest(short onlineId, UdpConnection connection) {
        synchronized(INSTANCE){return INSTANCE.dataRequest(onlineId,connection);}
    }
    private boolean dataRequest(short onlineId,UdpConnection connection) {
        if (!GameServer.server || INSTANCE.owner == null) return false;
        INSTANCE.requireOwner();
        ServerHuman human = INSTANCE.byId.get(onlineId);
        if (human == null) return false;
        if (connection != null && human.isRelevant(connection)) {
            ServerHumanTransport.announce(human, connection);
            ServerHumanTransport.idle(human, connection);
        }
        return true;
    }

    private short allocateId() {
        // Do not recycle IDs during a process lifetime: delayed client packets may
        // still refer to a removed actor. A later transport can add generations.
        while (nextId <= LAST_ID) {
            short id = (short)nextId++;
            if (!GameServer.IDToPlayerMap.containsKey(id) && !GameServer.IDToAddressMap.containsKey(id)) return id;
        }
        throw new IllegalStateException("Server-human presentation ID range exhausted; restart required");
    }

    static java.util.Collection<ServerHuman> dialogueHumans() {
        synchronized(INSTANCE){return INSTANCE.dialogueSnapshot();}
    }
    private java.util.Collection<ServerHuman> dialogueSnapshot() {
        INSTANCE.requireOwner();
        return java.util.List.copyOf(INSTANCE.humans.values());
    }

    static java.util.Collection<ServerHuman> activeZombieVictims() {
        synchronized(INSTANCE){return INSTANCE.zombieSnapshot();}
    }
    private java.util.Collection<ServerHuman> zombieSnapshot() {
        if (INSTANCE.owner == null || !INSTANCE.acceptMutations || INSTANCE.persistencePaused) return java.util.List.of();
        INSTANCE.requireOwner();
        return java.util.List.copyOf(INSTANCE.humans.values());
    }

    static ServerHuman activeZombieVictim(short id) {
        synchronized(INSTANCE){return INSTANCE.zombieVictim(id);}
    }
    private ServerHuman zombieVictim(short id) {
        if (INSTANCE.owner == null || !INSTANCE.acceptMutations || INSTANCE.persistencePaused) return null;
        INSTANCE.requireOwner();
        return INSTANCE.byId.get(id);
    }

    /** Trusted simulation-thread entry point. Network adapters must validate sender/target before calling. */
    public synchronized HumanStateStore.Result mutate(String key,int activation,long expectedRevision,HumanMutation mutation){
        requireOwner();java.util.Objects.requireNonNull(mutation);
        var logical=instances.get(key);
        if(logical==null||logical.state()!=HumanLifecycle.State.ACTIVE)return new HumanStateStore.Result(HumanStateStore.Status.INACTIVE,null);
        var state=logical.physiology().snapshot();
        if(!acceptMutations)return new HumanStateStore.Result(HumanStateStore.Status.UNAVAILABLE,state);
        if(logical.snapshot().activations()!=activation)return new HumanStateStore.Result(HumanStateStore.Status.STALE_ACTIVATION,state);
        return logical.physiology().dispatch(expectedRevision,mutation);
    }

    /** Bounded inbox for trusted off-thread producers; acknowledgement is in-memory, not crash durability. */
    public java.util.concurrent.CompletableFuture<HumanStateStore.Result> submitMutation(String key,int activation,long revision,HumanMutation mutation){
        java.util.Objects.requireNonNull(mutation);
        if(key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("Invalid NPC key");
        var result=new java.util.concurrent.CompletableFuture<HumanStateStore.Result>();
        if(!acceptMutations)result.complete(new HumanStateStore.Result(HumanStateStore.Status.UNAVAILABLE,null));
        else {
            var pending=new PendingMutation(key,activation,revision,mutation,result);
            if(!mutations.offer(pending))result.complete(new HumanStateStore.Result(HumanStateStore.Status.QUEUE_FULL,null));
            else if(!acceptMutations&&mutations.remove(pending))result.complete(new HumanStateStore.Result(HumanStateStore.Status.UNAVAILABLE,null));
        }
        return result;
    }
    private void rejectPending(HumanStateStore.Status status){
        for(int i=0;i<1024;i++){var command=mutations.poll();if(command==null)break;command.result().complete(new HumanStateStore.Result(status,null));}
    }
    public synchronized HumanState state(String key){requireOwner();var logical=instances.get(key);return logical==null?null:logical.physiology().snapshot();}
    /** Latest immutable sensor sample; reading does not scan or change contact memory. */
    public synchronized zombie.apocalipse.humans.brain.HumanSenses.Frame perception(String key){
        requireOwner();var human=humans.get(key);return human==null?null:human.perception.snapshot();
    }

    static ServerHuman tradingHuman(short id) {
        synchronized(INSTANCE){return INSTANCE.findTradingHuman(id);}
    }
    private ServerHuman findTradingHuman(short id) {
        if (!GameServer.server || INSTANCE.owner == null) return null;
        INSTANCE.requireOwner();
        return INSTANCE.byId.get(id);
    }

    private static String canonicalName(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private void requireOwner() {
        var running=worker;
        if (!GameServer.server || owner == null || Thread.currentThread() != owner && (running==null||!running.isWorkerThread())) {
            throw new IllegalStateException("ServerHumanFactory requires the initialized server thread or its NPC simulation worker");
        }
    }
}
