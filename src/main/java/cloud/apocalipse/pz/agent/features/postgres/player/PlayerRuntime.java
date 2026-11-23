package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.login.*;
import cloud.apocalipse.pz.agent.features.postgres.*;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.*;
import java.io.IOException;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.iso.IsoWorld;
import zombie.network.*;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.CreatePlayerPacket;
import zombie.network.packets.connection.LoadPlayerProfilePacket;
import zombie.savefile.ServerPlayerDB;
import static cloud.apocalipse.pz.agent.features.postgres.player.PlayerAccess.*;
import static cloud.apocalipse.pz.agent.features.postgres.player.PlayerReconstruction.set;

/** Native packet and storage linkage. JDBC reads and replay run only on bounded workers. */
public final class PlayerRuntime {
    private static volatile boolean closed;
    private record Accept(UdpConnection connection, int slot, String identity, IsoPlayer player) { }
    private record Creating(CreatePlayerPacket packet, UdpConnection connection, int slot) { }
    private static final ThreadLocal<Accept> ACCEPT = new ThreadLocal<>();
    private static final ThreadLocal<Creating> CREATING = new ThreadLocal<>();
    private static final java.util.concurrent.locks.ReentrantLock PROCESS = new java.util.concurrent.locks.ReentrantLock();
    private static final Map<Object, PlayerNativeSave> NATIVE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<IsoPlayer> FINALIZED_DEAD = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final ThreadLocal<Boolean> TEARDOWN = new ThreadLocal<>();
    public static void beginShutdown() { TEARDOWN.set(true); }
    public static void endShutdown() { TEARDOWN.remove(); }
    public static void beginProcess() { PROCESS.lock(); }
    public static void endProcess() { PROCESS.unlock(); }
    public static boolean enabled() { return PlayerState.enabled && !closed; }
    public static UUID characterId(IsoPlayer player) { return enabled()&&Thread.currentThread()==GameServer.mainThread ? PlayerMutations.loginCharacterId(player) : null; }
    private static boolean coop() { return GameServer.coop && SteamUtils.isSteamModeEnabled(); }
    private static String identity(UdpConnection connection) { return coop() ? connection.getIDStr() : connection.getUserName(); }
    private static void owner() { if (Thread.currentThread() != GameServer.mainThread && !Boolean.TRUE.equals(TEARDOWN.get())) throw new IllegalStateException("Player engine work requires the server thread or native quit phase"); }
    private static boolean ready(UdpConnection connection, int slot) {
        if (slot < 0 || slot >= 4 || connection.getPlayerAt(slot) != null) { ApocBRLoginGuard.deny(connection); return false; }
        return admitted(connection);
    }
    private static boolean admitted(UdpConnection connection) {
        if(!enabled()||!PlayerJournal.journaling()){ApocBRLoginGuard.deny(connection);return false;}
        return ApocBRLoginGuard.ready(connection);
    }
    public static void bootstrap(Object database) {
        String configured = AgentConfiguration.systemProperty(AgentConfiguration.PLAYER_JOURNAL_PROPERTY);
        if (configured == null) {
            String root = AgentConfiguration.systemProperty(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY);
            configured = (root == null ? Path.of(zombie.ZomboidFileSystem.instance.getSaveDir(), "Multiplayer", GameServer.serverName, "postgres-journal") : Path.of(root)).resolve("agent-players").toString();
        }
        set(database, "conn", null); set(database, "charactersToSave", new ConcurrentLinkedQueue<>());
        PlayerJournal.start(Path.of(configured), PlayerOperations::databaseMetrics);
    }
    public static void nativeCaptured(Object data, boolean serverEncoded) {
        Object bytes = get(data, "buffer");
        if (bytes != null) PayloadProvenance.remember(bytes, new PayloadProvenance.Metadata(serverEncoded ? DictionaryRuntime.localEncoding() : null, number(get(data, "worldVersion"))));
        var metadata = PayloadProvenance.metadata(bytes);
        NATIVE.put(data, new PlayerNativeSave(Core.gameSaveWorld, (String) get(data, "username"), (String) get(data, "steamid"),
            number(get(data, "playerIndex")), (String) get(data, "playerName"), ((Number) get(data, "x")).floatValue(), ((Number) get(data, "y")).floatValue(),
            ((Number) get(data, "z")).floatValue(), number(get(data, "worldVersion")), (boolean) get(data, "isDead"), (String) get(data, "splitUsername"),
            coop(), (byte[]) bytes, metadata == null ? null : metadata.origin()));
    }
    public static void nativeSave(Object data) {
        PlayerNativeSave snapshot = NATIVE.get(data);
        if (snapshot == null) throw new IllegalStateException("Native save lacks captured world/encoding identity");
        PlayerJournal.submit(snapshot); NATIVE.remove(data);
    }
    private static PlayerReader.Profile read(String world, String identity, int slot, boolean coop) throws java.sql.SQLException {
        if (!PlayerSync.readable(world, identity, slot, coop)) throw new java.sql.SQLException("Player persistence is pending", "55000");
        return new PlayerReader().load(world, identity, slot, coop);
    }
    public static void profile(Object request, Object transport) {
        owner(); var connection = (UdpConnection) transport; int slot = number(get(request, "playerIndex"));
        // Native clients request slot four to terminate a full four-character profile scan.
        if(slot<0||slot>=4){var reply=new LoadPlayerProfilePacket();set(reply,"playerIndex",(byte)slot);set(reply,"isExist",false);var output=connection.startPacket();PacketTypes.PacketType.LoadPlayerProfile.doPacket(output);reply.write(output);PacketTypes.PacketType.LoadPlayerProfile.send(connection);return;}
        if (!admitted(connection)) return;
        String world = Core.gameSaveWorld, identity = identity(connection), username = connection.getUserName(); boolean coop = coop();
        ApocBRAsyncLogin.submit(connection, "player-profile:" + slot, username, () -> {
            if (coop) convert(username, identity, world);
            return read(world, identity, slot, coop);
        }, profile -> {
            if (!admitted(connection)) return;
            try {
                var reply = new LoadPlayerProfilePacket(); set(reply, "playerIndex", (byte) slot); set(reply, "isExist", profile != null);
                if (profile != null) {
                    if (!PlayerSync.readable(world, identity, slot, coop)) throw new IllegalStateException("Player persistence changed");
                    IsoPlayer player = PlayerReconstruction.reconstruct(profile, slot);
                    try {
                        set(reply, "x", player.getX()); set(reply, "y", player.getY()); set(reply, "z", player.getZ());
                        set(reply, "buffer", encode(player)); set(reply, "worldVersion", IsoWorld.getWorldVersion()); set(reply, "isDead", player.isDead());
                        set(reply, "username", profile.one("snapshot").get("split_username") instanceof String split ? split : username);
                    } finally { discard(player); }
                }
                var output = connection.startPacket(); PacketTypes.PacketType.LoadPlayerProfile.doPacket(output); reply.write(output); PacketTypes.PacketType.LoadPlayerProfile.send(connection);
            } catch (IOException | RuntimeException | LinkageError failure) { failure("PROFILE", failure); ApocBRLoginGuard.deny(connection); }
        });
    }
    static byte[] encode(IsoPlayer player) throws IOException {
        if (DictionaryRuntime.localEncoding() == null) throw new IOException("Destination dictionary is unpublished");
        for (int size = Math.min(32768, PlayerLimits.SOURCE_BYTES); ; size = Math.min(size * 2, PlayerLimits.SOURCE_BYTES)) {
            var bytes = ByteBuffer.allocate(size);
            try { player.save(bytes); return Arrays.copyOf(bytes.array(), bytes.position()); }
            catch (BufferOverflowException full) { if (size >= PlayerLimits.SOURCE_BYTES) throw new IOException("Player profile exceeds payload budget"); }
        }
    }
    public static boolean connect(Object input, Object transport, String username, boolean initialWorld) {
        owner();
        if (ACCEPT.get() != null) {
            if(ACCEPT.get().connection()!=transport)throw new IllegalStateException("Nested player acceptance changed connection");
            return false;
        }
        if (!(transport instanceof UdpConnection connection)) { ApocBRLoginGuard.deny((IConnection) transport); return true; }
        var reader = (ByteBufferReader) input;
        if (reader.remaining() < 3 || reader.remaining() > PlayerLimits.SOURCE_BYTES) { ApocBRLoginGuard.deny(connection); return true; }
        var bytes = ByteBuffer.allocate(reader.remaining()); bytes.put(reader.bb.duplicate()).flip(); int slot = bytes.get(0);
        if (!ready(connection, slot)) return true;
        if (SteamUtils.isSteamModeEnabled() && !GameServer.coop && connection.getSteamId() <= 0) { ApocBRLoginGuard.deny(connection); return true; }
        String world = Core.gameSaveWorld, identity = identity(connection); boolean coop = coop();
        IsoPlayer created = PlayerCreation.take(connection, slot);
        if (created != null) { accept(connection, slot, identity, username, bytes, created, initialWorld); return true; }
        ApocBRAsyncLogin.submit(connection, "player-connect:" + slot, username, () -> read(world, identity, slot, coop), profile -> {
            if (!ready(connection, slot)) return;
            if (!Objects.equals(connection.getUserName(slot), username) || profile == null || !PlayerSync.readable(world, identity, slot, coop)) { ApocBRLoginGuard.deny(connection); return; }
            IsoPlayer player = PlayerReconstruction.reconstruct(profile, slot);
            try { PlayerSync.register(player, world, identity, slot, coop, profile); accept(connection, slot, identity, username, bytes, player, initialWorld); }
            catch (RuntimeException | LinkageError failure) { failure("CONNECT", failure); discard(player); throw failure; }
        });
        return true;
    }
    public static boolean connectPacket(Object input,Object connection) { return connect(input,connection,((IConnection)connection).getUserName(),true); }
    private static void accept(UdpConnection connection, int slot, String identity, String username, ByteBuffer bytes, IsoPlayer player, boolean initialWorld) {
        PlayerReconstruction.publishDescriptorIdentity(player); PlayerReconstruction.restoreEquipmentState(player, true);
        player.getCell().addMovingObject(player); player.setCurrentSquareFromPosition();
        ACCEPT.set(new Accept(connection, slot, identity, player));
        try {
            GameServer.receivePlayerConnect(new ByteBufferReader(bytes), connection, username);
            if (connection.getPlayerAt(slot) != player) { discard(player); return; }
            if (player.isOnFire()) zombie.iso.objects.IsoFireManager.AddBurningCharacter(player);
            if (initialWorld) { GameServer.sendInitialWorldState(connection); INetworkPacket.send(connection, PacketTypes.PacketType.MetaData, connection.getUserName()); }
        } finally { ACCEPT.remove(); }
    }
    public static Object loaded(int slot, String identity) {
        var accept = ACCEPT.get();
        if (accept == null || accept.slot() != slot || !Objects.equals(accept.identity(), identity)) throw new IllegalStateException("Native player load outside prepared acceptance");
        return accept.player();
    }
    public static boolean create(Object request, Object transport) {
        owner(); if (CREATING.get() != null) return false;
        var connection = (UdpConnection) transport; int slot = number(get(request, "playerIndex"));
        if (slot < 0 || slot >= 4 || !admitted(connection)) {
            if (slot < 0 || slot >= 4) ApocBRLoginGuard.deny(connection);
            discardRequestDescriptor(get(request,"survivorDescriptor"));
            return true;
        }
        var packet = new CreatePlayerPacket(); set(packet, "playerIndex", (byte) slot); set(packet, "spawnRegionName", get(request, "spawnRegionName"));
        set(packet, "survivorDescriptor", get(request, "survivorDescriptor"));
        @SuppressWarnings("unchecked") var traits = (List<Object>) get(packet, "characterTraits"); traits.addAll((Collection<?>) get(request, "characterTraits"));
        return PlayerCreation.defer(connection, slot, () -> {
            CREATING.set(new Creating(packet, connection, slot));
            try { packet.processServer(PacketTypes.PacketType.CreatePlayer, connection); }
            catch(RuntimeException|LinkageError failure) { failure("CREATE",failure); discard((IsoPlayer)get(packet,"player")); throw failure; }
            finally { CREATING.remove(); }
        },()->discardRequestDescriptor(get(packet,"survivorDescriptor")));
    }
    public static boolean afterNativeCreate() {
        var creating = CREATING.get(); if (creating == null) throw new IllegalStateException("Creation outside handoff");
        IsoPlayer player = (IsoPlayer) get(creating.packet(), "player");
        return PlayerCreation.created(creating.connection(), creating.slot(), player, () -> {
            var output = creating.connection().startPacket(); PacketTypes.PacketType.CreatePlayer.doPacket(output);
            creating.packet().write(output); PacketTypes.PacketType.CreatePlayer.send(creating.connection());
        });
    }
    public static void update() { if (enabled()) { PlayerCreation.update(); PlayerSync.flush(); } }
    public static void finalSave(Object player) { owner(); PlayerMutations.finalSample((IsoPlayer) player); PlayerSync.forceFlush(); }
    public static boolean finalizedDead(Object player) { return FINALIZED_DEAD.contains(player); }
    static void finalizeDead(IsoPlayer player, int slot, UdpConnection connection) {
        if (finalizedDead(player)) return;
        var database = ServerPlayerDB.getInstance();
        database.serverUpdateNetworkCharacter(player, slot, connection);
        database.process();
        // Co-op removes the old occupant after the creation reply. Stop its saves before publishing a new life.
        FINALIZED_DEAD.add(player);
        PlayerMutations.unregister(player);
    }
    public static void drain() { var database = ServerPlayerDB.isAvailable() ? ServerPlayerDB.getInstance() : null; if (database != null) database.process(); PlayerSync.forceFlush(); PlayerJournal.drain(); }
    public static void discard(IsoPlayer player) {
        if (player == null) return;
        player.getCell().getAddList().remove(player); player.getCell().getObjectList().remove(player); player.removeFromSquare();
        var descriptor = player.getDescriptor(); if (descriptor != null) IsoWorld.instance.survivorDescriptors.remove(descriptor.getID(), descriptor);
        PlayerMutations.unregister(player);
    }
    private static void discardRequestDescriptor(Object value) {
        if(value instanceof zombie.characters.SurvivorDesc descriptor&&descriptor.getInstance()==null)
            IsoWorld.instance.survivorDescriptors.remove(descriptor.getID(),descriptor);
    }
    public static void shutdown() {
        if (closed) return; closed = true;
        try {
            PlayerCreation.shutdown();
            var database = ServerPlayerDB.isAvailable() ? ServerPlayerDB.getInstance() : null;
            if (database != null) database.process();
            PlayerSync.forceFlush();
            PlayerJournal.drain();
        }
        finally { try { PlayerJournal.shutdown(); } finally { PlayerSync.shutdown(); NATIVE.clear(); FINALIZED_DEAD.clear(); } }
    }
    public static void closeDatabase() { if (Boolean.TRUE.equals(TEARDOWN.get())) drain(); else shutdown(); }
    static void failure(String stage,Throwable failure) { System.err.println("[ApocBR-Agent][Players] "+stage+"_FAILED; "+PlayerMutations.writeFailure(failure)); }
    private static void convert(String username, String steamid, String world) throws java.sql.SQLException {
        try (var connection = PostgresRuntime.borrowBackground()) {
            try (var query = connection.prepareStatement("UPDATE public.networkplayers SET steamid=? WHERE username=? AND world=? AND (steamid IS NULL OR steamid='')")) {
                query.setQueryTimeout(2); query.setString(1, steamid); query.setString(2, username); query.setString(3, world); query.executeUpdate();
            }
            // Existing relational identities are never silently reassigned; co-op conversion is a separate slot mode.
            connection.commit();
        }
    }
    public static void convertNative(String username, String steamid) {
        if (!ApocBRAsyncLogin.isWorker()) throw new IllegalStateException("Player identity conversion requires a JDBC worker");
        try { convert(username, steamid, Core.gameSaveWorld); } catch (java.sql.SQLException failure) { throw new IllegalStateException("Player co-op conversion failed"); }
    }
    private PlayerRuntime() { }
}
