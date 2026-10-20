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
        for (ServerHuman human : humans.values()) {
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
            human.brain.update(human.actuator.observe(now), human.actuator);
            // Simulate at 10 Hz, but publish routine motion only on the 2 Hz heartbeat.
            // Turns and starts/stops must replace stale client prediction immediately.
            boolean turned = Float.compare(previousDirection, human.player.getDirectionAngleRadians()) != 0;
            if (heartbeat || turned || wasMoving != human.moving) {
                for (UdpConnection connection : human.viewers) {
                    if (human.isRelevant(connection)) ServerHumanTransport.idle(human, connection);
                }
            }
        }
    }

    /** Returns the presentation online ID. Reusing a key requires explicit removal. */
    public short spawn(ServerHumanDefinition definition) {
        requireOwner();
        if (humans.containsKey(definition.key())) throw new IllegalArgumentException("Duplicate human key: " + definition.key());
        String name = canonicalName(definition.username());
        if (reservedNames.contains(name)) throw new IllegalArgumentException("Human username already used in this server process");
        for (zombie.characters.IsoPlayer player : GameServer.IDToPlayerMap.values()) {
            if (player.getUsername() != null && canonicalName(player.getUsername()).equals(name)) {
                throw new IllegalArgumentException("Human username conflicts with an online player");
            }
        }
        short id = allocateId();
        ServerHuman human = new ServerHuman(definition, id, IsoWorld.instance.currentCell);
        humans.put(definition.key(), human);
        byId.put(id, human);
        reservedNames.add(name);
        DebugLog.log("[ApocBR][ServerHumans] Spawned key=" + definition.key() + " id=" + id
            + " at=" + definition.x() + "," + definition.y() + "," + definition.z());
        return id;
    }

    public boolean remove(String key) {
        requireOwner();
        ServerHuman human = humans.remove(key);
        if (human == null) return false;
        byId.remove(human.player.getOnlineID());
        human.dispose();
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
