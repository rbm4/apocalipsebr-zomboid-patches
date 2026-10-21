package zombie.apocalipse.humans;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.text.Normalizer;
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
    private static final ServerHumanFactory INSTANCE = new ServerHumanFactory();
    private final Map<String, HumanLifecycle> instances = new LinkedHashMap<>();
    private final Map<String, ServerHuman> humans = new LinkedHashMap<>();
    private final Map<Short, ServerHuman> byId = new HashMap<>();
    private final Set<String> reservedNames = new HashSet<>();
    private Thread owner;
    private boolean configured;
    private int nextId = FIRST_ID;
    private long nextUpdate;
    private long nextBrainUpdate;

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
        if (!configured) {
            configured = true;
            try {
                ServerHumanDefinition guide = ServerHumanDefinition.configuredGuide();
                if (guide != null) spawn(guide);
            } catch (RuntimeException failure) {
                DebugLog.log("[ApocBR][ServerHumans] Guide startup failed: " + failure);
            }
        }
        if (now < nextBrainUpdate) return;
        nextBrainUpdate = now + BRAIN_UPDATE_NANOS;
        boolean heartbeat = now >= nextUpdate;
        if (heartbeat) nextUpdate = now + UPDATE_NANOS;
        java.util.List<String> dead = null;
        for (ServerHuman human : humans.values()) {
            if (human.player.isDead()) {
                if (dead == null) dead = new java.util.ArrayList<>();
                dead.add(human.definition.key());
                continue;
            }
            // Forget disconnected/replaced connection instances; do not retain them forever.
            Iterator<UdpConnection> iterator = human.viewers.iterator();
            while (iterator.hasNext()) {
                UdpConnection connection = iterator.next();
                if (!GameServer.udpEngine.connections.contains(connection) || !connection.isFullyConnected()) {
                    iterator.remove();
                }
            }
            for (UdpConnection connection : GameServer.udpEngine.connections) {
                if (!human.isRelevant(connection)) continue;
                if (!human.viewers.contains(connection)) ServerHumanTransport.announce(human, connection);
            }
            float previousDirection = human.player.getDirectionAngleRadians();
            boolean wasMoving = human.moving;
            ServerHumanTrading.update(human, now);
            ServerHumanDialogue.update(human, now);
            human.activity.update(now, human.actuator::observe, human.actuator);
            // Simulate at 10 Hz, but publish routine motion only on the 2 Hz heartbeat.
            // Turns and starts/stops must replace stale client prediction immediately.
            boolean turned = Float.compare(previousDirection, human.player.getDirectionAngleRadians()) != 0;
            if (heartbeat || turned || wasMoving != human.moving) {
                for (UdpConnection connection : human.viewers) {
                    if (human.isRelevant(connection)) ServerHumanTransport.idle(human, connection);
                }
            }
        }
        if (dead != null) for (String key : dead) markDead(key);
    }

    /** Register logical state independently of a live presentation. Simulation thread only. */
    public void registerDormant(ServerHumanDefinition definition) {
        requireOwner();
        ServerHumanProfiles.registry().require(definition.profileId());
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
            ServerHuman human = new ServerHuman(definition,id,IsoWorld.instance.currentCell);
            humans.put(key,human); byId.put(id,human); instance.active(human.profile.revision());
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
        if (death) instances.get(key).dead(); else instances.get(key).dormant();
        human.dispose();
        ServerHumanDialogue.rosterChanged();
        return true;
    }

    /** Permanent administrative removal; dormancy uses deactivate instead. */
    public boolean remove(String key) {
        requireOwner();
        if (!instances.containsKey(key)) return false;
        deactivate(key);
        instances.remove(key);
        return true;
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
