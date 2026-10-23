// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import zombie.ApocBRServerTelemetryLite;
import zombie.apocalipse.zombies.ServerZombieController;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieAI;
import zombie.characters.NetworkZombieVariables;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.network.statistics.data.NetworkStatistic;
import zombie.core.utils.UpdateLimit;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.ServerMap;
import zombie.network.packets.character.ZombieListPacket;
import zombie.network.packets.character.ZombiePacket;
import zombie.network.packets.character.ZombieSynchronizationPacket;

public class NetworkZombiePacker {
    private static final int ZOMBIE_PACKET_WORKERS = Math.max(1, Math.min(32, Integer.getInteger("apocbr.zombiePacketWorkers", 6)));
    private static final int RELAY_GRID_CELL_SIZE = 64;
    private static final int MAX_ASYNC_CONNECTIONS = 256; // Exact-build UdpEngine connection array capacity.
    private static final int ASYNC_CAPACITY = Math.max(1, Math.min(MAX_ASYNC_CONNECTIONS, Integer.getInteger("apocbr.zombieSendCapacity", 64)));
    // Constructor dependencies must initialize before the singleton is constructed.
    private static final NetworkZombiePacker instance = new NetworkZombiePacker();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeleted = new ArrayList<>();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeletedForSending = new ArrayList<>();
    private final HashSet<IsoZombie> zombiesReceived = new HashSet<>();
    private final ArrayList<IsoZombie> zombiesProcessing = new ArrayList<>();
    private final Map<Long, ArrayList<IsoZombie>> zombiesProcessingByCell = new HashMap<>();
    public final NetworkZombieList zombiesRequest = new NetworkZombieList();
    private final ZombiePacket packet = new ZombiePacket();
    private final Set<IConnection> extraUpdate = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean extraUpdateAll = new AtomicBoolean();
    public final Map<IConnection, List<Short>> zombiesToSend = new ConcurrentHashMap<>();
    UpdateLimit zombieSynchronizationReliableLimit = new UpdateLimit(5000L);
    private final ServerZombieAsyncSender asyncSender = new ServerZombieAsyncSender(ZOMBIE_PACKET_WORKERS, ASYNC_CAPACITY);
    private int nextConnectionStart;
    private long batchesSubmitted;
    private final Map<UdpConnection, ConnectionState> connectionStates = new IdentityHashMap<>();
    private final Map<ServerZombieAsyncSender.Lane, ConnectionState> statesByLane = new IdentityHashMap<>();
    private final ByteBuffer snapshotBuffer = ByteBuffer.allocate(ServerZombieAsyncSender.MAX_PACKET_BYTES);
    private final ServerZombiePacketPreparation packetPreparation = new ServerZombiePacketPreparation();

    public static NetworkZombiePacker getInstance() {
        return instance;
    }

    public void setExtraUpdate() {
        this.extraUpdateAll.set(true);
    }

    public void deleteZombie(IsoZombie z) {
        synchronized (this.zombiesDeleted) {
            this.zombiesDeleted.add(new NetworkZombiePacker.DeletedZombie(z.onlineId, z.getX(), z.getY()));
        }
    }

    public void parseZombie(ByteBufferReader bb, IConnection connection) {
        this.packet.parse(bb, connection);
        if (this.packet.id == -1) {
            DebugType.General.error("NetworkZombiePacker.parseZombie id=" + this.packet.id);
        } else {
            try {
                IsoZombie zombie = ServerMap.instance.zombieMap.get(this.packet.id);
                if (zombie == null) {
                    return;
                }

                if (zombie.getOwner() != connection) {
                    NetworkZombieManager.getInstance().recheck(connection);
                    this.extraUpdate.add(connection);
                    return;
                }

                this.applyZombie(zombie);
                ServerZombieController.getInstance().received(zombie, this.packet);
                zombie.lastRemoteUpdate = 0;
                if (!IsoWorld.instance.currentCell.getZombieList().contains(zombie)) {
                    IsoWorld.instance.currentCell.getZombieList().add(zombie);
                }

                if (!IsoWorld.instance.currentCell.getObjectList().contains(zombie)) {
                    IsoWorld.instance.currentCell.getObjectList().add(zombie);
                }

                if (zombie.isDead()) {
                    zombie.die();
                }

                zombie.zombiePacket.copy(this.packet);
                this.packetPreparation.received(zombie);
                IsoWorld.instance.currentCell.getPlayerSpatialQueries().invalidateZombies();
                zombie.zombiePacketUpdated = true;
                synchronized (this.zombiesReceived) {
                    this.zombiesReceived.add(zombie);
                }
            } catch (Exception var7) {
                DebugType.General.printException(var7, LogSeverity.Error);
            }
        }
    }

    /** Compatibility entry point: consumes completions, never waits for network workers. */
    public void awaitWorkers() {
        try (ApocBRServerTelemetryLite.Scope scope = ApocBRServerTelemetryLite.phase("network.zombies.completions")) {
            ServerZombieAsyncSender.Completion result;
            while ((result = this.asyncSender.poll()) != null) {
                try {
                    ConnectionState state = this.statesByLane.get(result.lane);
                    if (state != null) {
                        for (int i = 0; i < result.attempted; i++) {
                            ServerZombieAsyncSender.Message message = result.messages.get(i);
                            NetworkStatistic.getInstance().addOutcomePacket(message.id, message.size, state.connection);
                        }
                        if (!result.success) {
                            state.pendingDeletes.addAll(state.inFlightDeletes);
                            this.restoreRequests(state.connection, state.inFlightRequests);
                            state.forceAuth = true;
                            state.forceExtra = true;
                        }
                        state.inFlightDeletes = List.of();
                        state.inFlightRequests = List.of();
                    }
                } finally {
                    this.asyncSender.release(result.lane);
                }
            }
        }
    }

    public void shutdown() {
        ServerZombieController.getInstance().reset();
        for (ConnectionState state : this.connectionStates.values()) state.lane.close();
        this.asyncSender.shutdown();
        this.awaitWorkers();
        this.connectionStates.clear();
        this.statesByLane.clear();
    }

    public void postupdate() {
        this.awaitWorkers();
        this.zombiesToSend.clear();
        try (ApocBRServerTelemetryLite.Scope telemetry = ApocBRServerTelemetryLite.phase("network.zombies.auth")) {
            this.updateAuth();
        }
        synchronized (this.zombiesReceived) {
            this.zombiesProcessing.clear();
            this.zombiesProcessing.addAll(this.zombiesReceived);
            this.zombiesReceived.clear();
        }
        ServerZombieController.getInstance().addRelays(this.zombiesProcessing);
        this.rebuildZombiesProcessingGrid();
        synchronized (this.zombiesDeleted) {
            this.zombiesDeletedForSending.clear();
            this.zombiesDeletedForSending.addAll(this.zombiesDeleted);
            this.zombiesDeleted.clear();
        }

        Set<UdpConnection> connected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connection != null && connection.isFullyConnected() && !GameServer.isDelayedDisconnect(connection)) connected.add(connection);
        }
        java.util.Iterator<Map.Entry<UdpConnection, ConnectionState>> iterator = this.connectionStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UdpConnection, ConnectionState> entry = iterator.next();
            if (!connected.contains(entry.getKey())) {
                entry.getValue().lane.close();
                this.statesByLane.remove(entry.getValue().lane);
                this.extraUpdate.remove(entry.getKey());
                iterator.remove();
            }
        }
        boolean forceExtraUpdate = this.extraUpdateAll.getAndSet(false);
        ArrayList<UdpConnection> eligible = new ArrayList<>();
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connected.contains(connection)) eligible.add(connection);
        }
        // Rotate admission so a full global budget cannot permanently favor the first connections.
        int admissionStart = this.nextConnectionStart;
        int following = admissionStart;
        for (int offset = 0; offset < eligible.size(); offset++) {
            UdpConnection connection = eligible.get((admissionStart + offset) % eligible.size());
            ConnectionState state = this.stateFor(connection);
            if (state == null) continue;
            state.forceExtra |= forceExtraUpdate;
            long before = this.batchesSubmitted;
            this.send(connection);
            if (this.batchesSubmitted != before) following = (admissionStart + offset + 1) % eligible.size();
        }
        this.nextConnectionStart = eligible.isEmpty() ? 0 : following;
    }

    private ConnectionState stateFor(UdpConnection connection) {
        ConnectionState state = this.connectionStates.get(connection);
        if (state == null && this.connectionStates.size() < MAX_ASYNC_CONNECTIONS) {
            // Capture the endpoint here: the transport lambda must not retain a UdpConnection.
            RakNetPeerInterface peer = connection.getPeer();
            long guid = connection.getConnectedGUID();
            ServerZombieAsyncSender.Lane lane = new ServerZombieAsyncSender.Lane(
                (data, priority, reliability, ordering) -> peer.SendRaw(data, priority, reliability, ordering, guid, false));
            state = new ConnectionState(connection, lane);
            this.connectionStates.put(connection, state);
            this.statesByLane.put(lane, state);
        }
        return state;
    }

    private void updateAuth() {
        ArrayList<IsoZombie> zombies = IsoWorld.instance.currentCell.getZombieList();
        ServerZombieController.getInstance().beginFrame();
        NetworkZombieManager.getInstance().beginAuthUpdate();
        this.packetPreparation.beginFrame();
        long ownershipNanos = 0L, bookkeepingNanos = 0L;
        for (int i = 0; i < zombies.size(); i++) {
            IsoZombie zombie = zombies.get(i);
            long started = System.nanoTime();
            NetworkZombieManager.getInstance().updateAuth(zombie);
            long prepared = System.nanoTime();
            this.packetPreparation.bookkeep(zombie);
            ownershipNanos += prepared - started;
            bookkeepingNanos += System.nanoTime() - prepared;
        }
        ApocBRServerTelemetryLite.recordPhase("network.zombies.ownership", ownershipNanos);
        ApocBRServerTelemetryLite.recordPhase("network.zombies.packetBookkeeping", bookkeepingNanos);
        ApocBRServerTelemetryLite.count("zombies.packet.bookkeeping", zombies.size());
    }

    public int getZombieData(UdpConnection connection, ZombieSynchronizationPacket packet) {
        Collection<IsoZombie> requestSnapshot = this.createRequestSnapshot(connection);
        List<Short> sent = new ArrayList<>();
        this.zombiesToSend.put(connection, sent);
        return this.getZombieData(connection, packet, requestSnapshot, sent);
    }

    public void send(UdpConnection connection) {
        try (ApocBRServerTelemetryLite.Scope dispatch = ApocBRServerTelemetryLite.phase("network.zombies.dispatch")) {
            if (!connection.isFullyConnected() || GameServer.isDelayedDisconnect(connection)) return;
            ConnectionState state = this.stateFor(connection);
            if (state == null) return;
            for (DeletedZombie deleted : this.zombiesDeletedForSending) {
                if (connection.isRelevantTo(deleted.x, deleted.y)) state.pendingDeletes.add(deleted.onlineId);
            }
            state.pendingRelays.removeIf(zombie -> !this.isRelayRelevant(connection, zombie));
            HashSet<IsoZombie> candidates = new HashSet<>();
            this.getRelayCandidates(connection, candidates);
            for (IsoZombie zombie : candidates) {
                if (this.isRelayRelevant(connection, zombie) && state.pendingRelays.size() < 300) state.pendingRelays.add(zombie);
            }
            if (!this.asyncSender.reserve(state.lane)) {
                ApocBRServerTelemetryLite.count("zombies.async.backpressure", 1);
                return;
            }
            try (ApocBRServerTelemetryLite.Scope scope = ApocBRServerTelemetryLite.phase("network.zombies.snapshot")) {
                this.captureAndSubmit(state);
            } catch (Exception exception) {
                this.restoreRequests(connection, state.inFlightRequests);
                state.inFlightRequests = List.of();
                state.forceAuth = true;
                state.forceExtra = true;
                this.asyncSender.release(state.lane);
                DebugType.General.printException(exception, LogSeverity.Error);
            }
        }
    }

    private boolean isRelayRelevant(UdpConnection connection, IsoZombie zombie) {
        return zombie.onlineId != -1 && ServerMap.instance.zombieMap.get(zombie.onlineId) == zombie
            && (zombie.getOwner() != null || ServerZombieController.getInstance().controls(zombie)) && zombie.getOwner() != connection
            && connection.RelevantTo(zombie.getX(), zombie.getY(), (connection.getRelevantRange() - 2) * 10);
    }

    private Collection<IsoZombie> createRequestSnapshot(UdpConnection connection) {
        NetworkZombieList.NetworkZombie requests = this.zombiesRequest.getNetworkZombie(connection);
        LinkedHashSet<IsoZombie> snapshot = new LinkedHashSet<>();
        synchronized (requests.zombies) {
            while (snapshot.size() < 300 && !requests.zombies.isEmpty()) {
                snapshot.add(requests.zombies.poll());
            }
        }

        HashSet<IsoZombie> relayCandidates = new HashSet<>();
        this.getRelayCandidates(connection, relayCandidates);
        for (IsoZombie zombie : relayCandidates) {
            if (snapshot.size() >= 300) {
                break;
            }

            if ((zombie.getOwner() != null || ServerZombieController.getInstance().controls(zombie))
                && zombie.getOwner() != connection
                && connection.RelevantTo(zombie.getX(), zombie.getY(), (connection.getRelevantRange() - 2) * 10)
                && zombie.onlineId != -1) {
                snapshot.add(zombie);
            }
        }

        return snapshot;
    }

    private int getZombieData(
        UdpConnection connection,
        ZombieSynchronizationPacket packet,
        Collection<IsoZombie> requestSnapshot,
        List<Short> zombiesToSend
    ) {
        packet.sendQueue.clear();
        int count = 0;
        try {
            for (IsoZombie zombie : requestSnapshot) {
                if (zombie != null && zombie.onlineId != -1) {
                    this.packetPreparation.prepare(zombie);
                    packet.sendQueue.add(zombie);
                    zombiesToSend.add(zombie.getOnlineID());
                    if (++count >= 300) {
                        return count;
                    }
                }
            }

        } catch (BufferOverflowException exception) {
            DebugType.General.printException(exception, LogSeverity.Error);
        }

        return count;
    }

    private void rebuildZombiesProcessingGrid() {
        this.zombiesProcessingByCell.clear();
        for (IsoZombie zombie : this.zombiesProcessing) {
            if ((zombie.getOwner() != null || ServerZombieController.getInstance().controls(zombie)) && zombie.onlineId != -1) {
                this.zombiesProcessingByCell.computeIfAbsent(key(cellFor(zombie.getX()), cellFor(zombie.getY())), ignored -> new ArrayList<>()).add(zombie);
            }
        }
    }

    private void getRelayCandidates(UdpConnection connection, HashSet<IsoZombie> output) {
        int radius = (connection.getRelevantRange() - 2) * 10;
        for (IsoPlayer player : connection.players) {
            if (player != null && player.isAlive()) {
                this.addRelayCells(
                    cellFor(player.getX() - radius),
                    cellFor(player.getX() + radius),
                    cellFor(player.getY() - radius),
                    cellFor(player.getY() + radius),
                    output
                );
            }
        }

        for (int index = 0; index < connection.connectArea.length; index++) {
            if (connection.connectArea[index] != null) {
                int chunkMapWidth = (int)connection.connectArea[index].z;
                int minX = PZMath.fastfloor(connection.connectArea[index].x - chunkMapWidth / 2) * 8;
                int minY = PZMath.fastfloor(connection.connectArea[index].y - chunkMapWidth / 2) * 8;
                int maxX = minX + chunkMapWidth * 8;
                int maxY = minY + chunkMapWidth * 8;
                this.addRelayCells(cellFor(minX), cellFor(maxX), cellFor(minY), cellFor(maxY), output);
            }
        }
    }

    private void addRelayCells(int minCellX, int maxCellX, int minCellY, int maxCellY, HashSet<IsoZombie> output) {
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellY = minCellY; cellY <= maxCellY; cellY++) {
                ArrayList<IsoZombie> zombies = this.zombiesProcessingByCell.get(key(cellX, cellY));
                if (zombies != null) {
                    output.addAll(zombies);
                }
            }
        }
    }

    private static int cellFor(float value) {
        return PZMath.fastfloor(value / RELAY_GRID_CELL_SIZE);
    }

    private static long key(int cellX, int cellY) {
        return ((long)cellX & 4294967295L) << 32 | (long)cellY & 4294967295L;
    }

    private ServerZombieAsyncSender.Message freeze(PacketTypes.PacketType type, byte[]... bodies) {
        return new ServerZombieAsyncSender.Message(type.getId(), type.packetPriority, type.packetReliability, type.orderingChannel, bodies);
    }

    private byte[] freezeIds(List<Short> ids) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + ids.size() * 2);
        buffer.putShort((short)ids.size());
        for (short id : ids) buffer.putShort(id);
        return buffer.array();
    }

    private void restoreRequests(UdpConnection connection, List<RequestedZombie> requests) {
        NetworkZombieList.NetworkZombie pending = this.zombiesRequest.getNetworkZombie(connection);
        synchronized (pending.zombies) {
            // Failed attempts precede requests that arrived while the batch was in flight.
            for (int i = requests.size() - 1; i >= 0; i--) {
                RequestedZombie request = requests.get(i);
                IsoZombie zombie = request.zombie;
                if (zombie != null && zombie.onlineId == request.onlineId && zombie.onlineId != -1
                    && ServerMap.instance.zombieMap.get(zombie.onlineId) == zombie
                    && !pending.zombies.contains(zombie)) pending.zombies.addFirst(zombie);
            }
        }
    }

    private void captureAndSubmit(ConnectionState state) {
        UdpConnection connection = state.connection;
        List<ServerZombieAsyncSender.Message> messages = new ArrayList<>();
        List<Short> deletes = new ArrayList<>(state.pendingDeletes);
        // Client parses signed-short counts. Split retained deletions into bounded packets.
        for (int i = 0; i < deletes.size(); i += 300) {
            messages.add(this.freeze(PacketTypes.PacketType.ZombieDeleteOnClient,
                this.freezeIds(deletes.subList(i, Math.min(i + 300, deletes.size())))));
        }
        ZombieListPacket listPacket = (ZombieListPacket)connection.getPacket(PacketTypes.PacketType.ZombieList);
        int newHash;
        try (ApocBRServerTelemetryLite.Scope scope = ApocBRServerTelemetryLite.phase("network.zombies.snapshot.authorization")) {
            newHash = NetworkZombieManager.getInstance().getZombieAuth(connection, listPacket);
        }
        boolean hashChanged = state.forceAuth || connection.getZombieListHash() != newHash;
        boolean sendAuth = hashChanged || !listPacket.zombiesAuth.isEmpty() && connection.zombieListRefresh.Check();
        if (sendAuth) messages.add(this.freeze(PacketTypes.PacketType.ZombieList, this.freezeIds(listPacket.zombiesAuth)));

        NetworkZombieList.NetworkZombie pending = this.zombiesRequest.getNetworkZombie(connection);
        LinkedHashSet<IsoZombie> selected = new LinkedHashSet<>();
        List<RequestedZombie> consumed = new ArrayList<>();
        state.inFlightRequests = consumed; // Also retained for rollback if capture throws.
        synchronized (pending.zombies) {
            while (consumed.size() < 300 && !pending.zombies.isEmpty()) {
                IsoZombie zombie = pending.zombies.poll();
                consumed.add(new RequestedZombie(zombie));
                if (zombie != null && zombie.onlineId != -1) selected.add(zombie);
            }
            for (IsoZombie zombie : state.pendingRelays) {
                if (selected.size() >= 300) break;
                if (this.isRelayRelevant(connection, zombie)) selected.add(zombie);
            }
            if (hashChanged) {
                // One membership snapshot avoids quadratic LinkedList.contains per owner ID.
                Set<IsoZombie> deferred = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                deferred.addAll(pending.zombies);
                for (short id : listPacket.zombiesAuth) {
                    IsoZombie zombie = ServerMap.instance.zombieMap.get(id);
                    if (zombie != null && zombie.onlineId != -1 && !selected.contains(zombie)) {
                        if (selected.size() < 300) selected.add(zombie);
                        else if (deferred.add(zombie)) pending.zombies.add(zombie);
                    }
                }
            }
        }
        List<RequestedZombie> selectedRequests = new ArrayList<>();
        for (IsoZombie zombie : selected) selectedRequests.add(new RequestedZombie(zombie));
        state.inFlightRequests = selectedRequests;
        List<byte[]> fields = new ArrayList<>();
        List<Short> sent = new ArrayList<>();
        int packetBytes = 6;
        for (IsoZombie zombie : selected) {
            if (zombie.onlineId == -1 || ServerMap.instance.zombieMap.get(zombie.onlineId) != zombie) continue;
            this.packetPreparation.prepare(zombie);
            this.snapshotBuffer.clear();
            zombie.zombiePacket.write(this.snapshotBuffer);
            int bytes = this.snapshotBuffer.position();
            if (packetBytes + bytes > ServerZombieAsyncSender.MAX_PACKET_BYTES) {
                this.restoreRequests(connection, List.of(new RequestedZombie(zombie)));
                ApocBRServerTelemetryLite.count("zombies.async.packetCapacityDeferred", 1);
                continue;
            }
            packetBytes += bytes;
            fields.add(Arrays.copyOf(this.snapshotBuffer.array(), bytes));
            sent.add(zombie.onlineId);
        }
        boolean sendSync = !fields.isEmpty() || connection.timerSendZombie.check() || state.forceExtra || this.extraUpdate.contains(connection);
        if (sendSync) {
            byte[][] parts = new byte[fields.size() + 1][];
            parts[0] = ByteBuffer.allocate(3).put((byte)(connection.isNeighborPlayer() ? 1 : 0)).putShort((short)fields.size()).array();
            for (int i = 0; i < fields.size(); i++) parts[i + 1] = fields.get(i);
            PacketTypes.PacketType type = this.zombieSynchronizationReliableLimit.Check()
                ? PacketTypes.PacketType.ZombieSynchronizationReliable : PacketTypes.PacketType.ZombieSynchronizationUnreliable;
            messages.add(this.freeze(type, parts));
        }
        if (messages.isEmpty()) {
            state.inFlightRequests = List.of();
            this.asyncSender.release(state.lane);
            return;
        }
        state.inFlightDeletes = List.copyOf(deletes);
        this.asyncSender.submit(state.lane, messages);
        this.batchesSubmitted++;
        state.pendingDeletes.clear();
        state.pendingRelays.removeIf(zombie -> selected.contains(zombie) || !this.isRelayRelevant(connection, zombie));
        state.forceAuth = false;
        if (sendAuth) {
            connection.setZombieListHash(newHash);
            connection.zombieListRefresh.Reset();
        }
        if (sendSync) {
            this.extraUpdate.remove(connection);
            state.forceExtra = false;
            connection.timerSendZombie.reset(3800L);
        }
        this.zombiesToSend.put(connection, sent);
        ApocBRServerTelemetryLite.count("zombieJobs.submitted", 1);
        ApocBRServerTelemetryLite.count("zombies.async.snapshotBytes", messages.stream().mapToLong(message -> message.size).sum());
    }

    private void applyZombie(IsoZombie zombie) {
        IsoGridSquare g = IsoWorld.instance
            .currentCell
            .getGridSquare(PZMath.fastfloor(this.packet.x), PZMath.fastfloor(this.packet.y), PZMath.fastfloor((float)this.packet.z));
        zombie.setLastX(zombie.setNextX(zombie.setX(this.packet.realX)));
        zombie.setLastY(zombie.setNextY(zombie.setY(this.packet.realY)));
        zombie.setLastZ(zombie.setZ(this.packet.realZ));
        zombie.setDirectionAngle(this.packet.dirAngleRads * (180.0F / (float)Math.PI));
        zombie.setCurrent(g);
        if (g != zombie.getMovingSquare()) {
            zombie.setMovingSquareNow();
        }

        NetworkZombieAI networkAi = zombie.getNetworkCharacterAI();
        networkAi.targetX = this.packet.x;
        networkAi.targetY = this.packet.y;
        networkAi.targetZ = this.packet.z;
        networkAi.predictionType = this.packet.predictionType;
        zombie.setHealth(this.packet.health / 1000.0F);
        zombie.setSpeedMod(this.packet.speedMod / 1000.0F);
        if (this.packet.target == -1) {
            zombie.setTargetSeenTime(0.0F);
            zombie.target = null;
        } else {
            IsoPlayer target = null;
            if (GameClient.client) {
                target = GameClient.IDToPlayerMap.get(this.packet.target);
            } else if (GameServer.server) {
                target = GameServer.IDToPlayerMap.get(this.packet.target);
            }

            if (target != zombie.target) {
                zombie.setTargetSeenTime(0.0F);
                zombie.target = target;
            }
        }

        zombie.timeSinceSeenFlesh = this.packet.timeSinceSeenFlesh;
        zombie.set(ZombieTurnAlerted.TARGET_ANGLE, this.packet.smParamTargetAngle / 1000.0F);
        NetworkZombieVariables.setBooleanVariables(zombie, this.packet.booleanVariables);
        zombie.setWalkType(this.packet.walkType.toString());
        zombie.setSpeedTypeFromWalkType();
        zombie.realState = this.packet.realState;
    }

    /** Retried references must still represent the captured ID, including after pooled reuse. */
    private static final class RequestedZombie {
        final IsoZombie zombie;
        final short onlineId;
        RequestedZombie(IsoZombie zombie) {
            this.zombie = zombie;
            this.onlineId = zombie == null ? -1 : zombie.onlineId;
        }
    }

    /** Main-thread bookkeeping; never passed to a worker. */
    private static final class ConnectionState {
        final UdpConnection connection;
        final ServerZombieAsyncSender.Lane lane;
        final LinkedHashSet<Short> pendingDeletes = new LinkedHashSet<>(); // Bounded by short-ID domain.
        final LinkedHashSet<IsoZombie> pendingRelays = new LinkedHashSet<>(); // At most one 300-zombie batch.
        List<Short> inFlightDeletes = List.of();
        List<RequestedZombie> inFlightRequests = List.of();
        boolean forceAuth;
        boolean forceExtra;
        ConnectionState(UdpConnection connection, ServerZombieAsyncSender.Lane lane) {
            this.connection = connection;
            this.lane = lane;
        }
    }

    public class DeletedZombie {
        public short onlineId;
        public float x;
        public float y;

        public DeletedZombie(final short onlineId, final float x, final float y) {
            Objects.requireNonNull(NetworkZombiePacker.this);
            super();
            this.onlineId = onlineId;
            this.x = x;
            this.y = y;
        }
    }
}
