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
 * Simulation-thread factory and lifecycle owner for server-owned human replicas.
 * Future subscription workers must hand immutable definitions to the simulation
 * thread; they must never construct characters or send packets themselves.
 */
public final class ServerHumanFactory {
    // Vanilla has 255 connection slots with four player IDs each (0..1019).
    // Keep IDs modest: VoiceManagerData uses an ID-indexed array on clients.
    static final int FIRST_ID = 1024;
    static final int LAST_ID = 1087;
    static final long UPDATE_NANOS = 500_000_000L;
    static final long BRAIN_UPDATE_NANOS = 100_000_000L;
    static final long CHECKPOINT_NANOS = Math.max(1000,Math.min(60000,
        Long.getLong("apocbr.npc.persistence.checkpointMs",30000L)))*1_000_000L;
    private static final ServerHumanFactory INSTANCE = new ServerHumanFactory();
    private final Map<String, HumanLifecycle> instances = new LinkedHashMap<>();
    private final Map<String, ServerHuman> humans = new LinkedHashMap<>();
    private final Map<Short, ServerHuman> byId = new HashMap<>();
    private final Set<String> reservedNames = new HashSet<>();
    private Thread owner;
    private boolean configured;
    private zombie.savefile.ApocBRNpcStore store;
    private final Map<String,zombie.savefile.ApocBRNpcStore.State> checkpoints=new HashMap<>();
    private long nextCheckpoint;
    private boolean persistencePaused;
    private boolean configurationRejected;
    private final Set<String> retiredKeys=new HashSet<>();
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

    /** Called by GameServer once per active tick, after world initialization. */
    public static void update() {
        if (!GameServer.server || GameServer.udpEngine == null
            || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
        INSTANCE.tick(System.nanoTime());
    }

    void tick(long now) {
        if (owner == null) owner = Thread.currentThread();
        requireOwner();
        ServerHumanDialogue.install();
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
                    if(activeCount>LAST_ID-FIRST_ID+1)throw new IllegalArgumentException("Too many active NPCs");
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
                    checkpoints.put(state.definition().key(),state);
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
                    for(var viewer:human.viewers)ServerHumanTransport.idle(human,viewer);
                }
                persistencePaused=true;return;
            }
            if(persistencePaused)for(var human:humans.values())human.activity.setBlocked(false,now,human.actuator);
            persistencePaused=false;
        }
        if (now < nextBrainUpdate) return;
        nextBrainUpdate = now + BRAIN_UPDATE_NANOS;
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
    public void registerDormant(ServerHumanDefinition definition) {
        requireOwner();
        ServerHumanProfiles.registry().require(definition.profileId());
        if(instances.size()+retiredKeys.size()>=256)throw new IllegalStateException("NPC logical instance limit reached");
        if(retiredKeys.contains(definition.key()))throw new IllegalArgumentException("NPC key permanently retired");
        if (instances.containsKey(definition.key())) throw new IllegalArgumentException("Duplicate human key: " + definition.key());
        String name = canonicalName(definition.username());
        if (reservedNames.contains(name)) throw new IllegalArgumentException("Human username already used in this server process");
        checkOnlineName(definition);
        instances.put(definition.key(),new HumanLifecycle(definition));
        reservedNames.add(name);
    }

    /** Compatibility spawn creates a logical instance and activates it immediately. */
    public short spawn(ServerHumanDefinition definition) {
        registerDormant(definition);
        return activate(definition.key());
    }

    public short activate(String key) {
        requireOwner();
        HumanLifecycle instance = instances.get(key);
        if (instance == null) throw new IllegalArgumentException("Unknown human key: " + key);
        ServerHumanDefinition definition = instance.definition();
        ServerHumanProfiles.registry().require(definition.profileId());
        checkOnlineName(definition);
        instance.spawning();
        try {
            short id = allocateId();
            if(instance.physiology().snapshot().dead())throw new IllegalStateException("Dead NPC physical state cannot activate");
            ServerHuman human = new ServerHuman(definition,id,IsoWorld.instance.currentCell,instance.physiology());
            var restored=checkpoints.get(key);
            if(restored!=null){human.player.setX(restored.x());human.player.setY(restored.y());human.player.setZ(restored.z());human.player.realx=restored.x();human.player.realy=restored.y();human.player.realz=(byte)restored.z();human.postX=restored.x();human.postY=restored.y();}
            humans.put(key,human); byId.put(id,human); instance.active(human.profile.revision());
            checkpoint(key,false);
            DebugLog.log("[ApocBR][ServerHumans] Spawned key=" + key + " profile=" + definition.profileId()
                + " id=" + id + " at=" + definition.x() + "," + definition.y() + "," + definition.z());
            ServerHumanDialogue.rosterChanged();
            return id;
        } catch (RuntimeException failure) {
            if (instance.state() == HumanLifecycle.State.SPAWNING) instance.dormant();
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

    public HumanLifecycle.Snapshot lifecycle(String key) {
        requireOwner();
        HumanLifecycle instance = instances.get(key);
        return instance == null ? null : instance.snapshot();
    }

    public boolean deactivate(String key) { return retire(key,false); }
    public boolean markDead(String key) { return retire(key,true); }
    private boolean retire(String key, boolean death) {
        requireOwner();
        ServerHuman human = humans.remove(key);
        if (human == null) return false;
        byId.remove(human.player.getOnlineID());
        if (death) {instances.get(key).physiology().dispatch(new HumanMutation.Kill());instances.get(key).dead();}
        else instances.get(key).dormant();
        checkpoint(key,false,human);
        human.dispose();
        ServerHumanDialogue.rosterChanged();
        return true;
    }

    /** Permanent administrative removal; dormancy uses deactivate instead. */
    public boolean remove(String key) {
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
        var state=new zombie.savefile.ApocBRNpcStore.State(d,logical.state(),logical.activations(),logical.snapshot().profileRevision(),x,y,z,revision,retired,logical.physiology().snapshot());
        if(old!=null&&old.equals(state))return;
        state=new zombie.savefile.ApocBRNpcStore.State(d,state.lifecycle(),state.activations(),state.profileRevision(),x,y,z,revision+1,retired,state.physiology());
        store.offer(state);checkpoints.put(key,state);
        HumanLog.checkpoint(old,state);
    }

    /** Fallback for vanilla clients discovering/reloading an unknown remote-player ID. */
    public static boolean handleDataRequest(short onlineId, UdpConnection connection) {
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
        INSTANCE.requireOwner();
        return java.util.List.copyOf(INSTANCE.humans.values());
    }

    /** Trusted simulation-thread entry point. Network adapters must validate sender/target before calling. */
    public HumanStateStore.Result mutate(String key,int activation,long expectedRevision,HumanMutation mutation){
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
        else if(!mutations.offer(new PendingMutation(key,activation,revision,mutation,result)))
            result.complete(new HumanStateStore.Result(HumanStateStore.Status.QUEUE_FULL,null));
        return result;
    }
    private void rejectPending(HumanStateStore.Status status){
        for(int i=0;i<1024;i++){var command=mutations.poll();if(command==null)break;command.result().complete(new HumanStateStore.Result(status,null));}
    }
    public HumanState state(String key){requireOwner();var logical=instances.get(key);return logical==null?null:logical.physiology().snapshot();}
    /** Latest immutable sensor sample; reading does not scan or change contact memory. */
    public zombie.apocalipse.humans.brain.HumanSenses.Frame perception(String key){
        requireOwner();var human=humans.get(key);return human==null?null:human.perception.snapshot();
    }

    static ServerHuman tradingHuman(short id) {
        if (!GameServer.server || INSTANCE.owner == null) return null;
        INSTANCE.requireOwner();
        return INSTANCE.byId.get(id);
    }

    private static String canonicalName(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private void requireOwner() {
        if (!GameServer.server || owner == null || Thread.currentThread() != owner) {
            throw new IllegalStateException("ServerHumanFactory must run on the initialized server simulation thread");
        }
    }
}
