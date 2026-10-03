// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import zombie.ApocBRServerTelemetryLite;

import java.nio.BufferOverflowException;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.ZombieListPacket;
import zombie.network.packets.character.ZombiePacket;
import zombie.network.packets.character.ZombieSynchronizationPacket;

public class NetworkZombiePacker {
    private static final int ZOMBIE_PACKET_WORKERS = Math.max(1, Integer.getInteger("apocbr.zombiePacketWorkers", 6));
    private static final int RELAY_GRID_CELL_SIZE = 64;
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
    private final ExecutorService zombiePacketPool = Executors.newFixedThreadPool(ZOMBIE_PACKET_WORKERS);
    private final ConcurrentLinkedQueue<NetworkZombiePacker.ConnectionResult> completedJobs = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch pendingLatch;

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
                zombie.zombiePacketUpdated = true;
                synchronized (this.zombiesReceived) {
                    this.zombiesReceived.add(zombie);
                }
            } catch (Exception var7) {
                DebugType.General.printException(var7, LogSeverity.Error);
            }
        }
    }

    public void awaitWorkers() {
        CountDownLatch latch = this.pendingLatch;
        if (latch != null) {
            try {
                try (ApocBRServerTelemetryLite.Scope telemetry = ApocBRServerTelemetryLite.phase("network.zombies.workerWait")) {
                    latch.await();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                this.pendingLatch = null;
            }
        }

        NetworkZombiePacker.ConnectionResult result;
        while ((result = this.completedJobs.poll()) != null) {
            try (ApocBRServerTelemetryLite.Scope telemetry = ApocBRServerTelemetryLite.phase("network.zombies.send")) {
                this.send(result.connection, result.requestSnapshot, result.forceExtraUpdate);
            }
        }
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
        this.rebuildZombiesProcessingGrid();

        synchronized (this.zombiesDeleted) {
            this.zombiesDeletedForSending.clear();
            this.zombiesDeletedForSending.addAll(this.zombiesDeleted);
            this.zombiesDeleted.clear();
        }

        ArrayList<UdpConnection> connections = new ArrayList<>();
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connection != null && connection.isFullyConnected()) {
                connections.add(connection);
            }
        }

        ApocBRServerTelemetryLite.count("zombieJobs.submitted", connections.size());
        boolean forceExtraUpdate = this.extraUpdateAll.getAndSet(false);
        CountDownLatch latch = new CountDownLatch(connections.size());
        for (UdpConnection connection : connections) {
            NetworkZombieList.NetworkZombie requests = this.zombiesRequest.getNetworkZombie(connection);
            ArrayList<IsoZombie> snapshot = new ArrayList<>(300);
            synchronized (requests.zombies) {
                while (snapshot.size() < 300 && !requests.zombies.isEmpty()) {
                    snapshot.add(requests.zombies.poll());
                }
            }

            Collection<IsoZombie> requestSnapshot = new LinkedHashSet<>(snapshot);
            this.zombiePacketPool.execute(new NetworkZombiePacker.ConnectionJob(connection, requestSnapshot, forceExtraUpdate, latch));
        }

        this.pendingLatch = latch;
    }

    private void updateAuth() {
        ArrayList<IsoZombie> zombies = IsoWorld.instance.currentCell.getZombieList();
        NetworkZombieManager.getInstance().beginAuthUpdate();
        long ownershipNanos = 0L, packetNanos = 0L;
        for (int i = 0; i < zombies.size(); i++) {
            IsoZombie zombie = zombies.get(i);
            long started = System.nanoTime();
            NetworkZombieManager.getInstance().updateAuth(zombie);
            long prepared = System.nanoTime();
            zombie.zombiePacket.set(zombie);
            ownershipNanos += prepared - started;
            packetNanos += System.nanoTime() - prepared;
        }
        ApocBRServerTelemetryLite.recordPhase("network.zombies.ownership", ownershipNanos);
        ApocBRServerTelemetryLite.recordPhase("network.zombies.packetPrepare", packetNanos);
    }

    public int getZombieData(UdpConnection connection, ZombieSynchronizationPacket packet) {
        Collection<IsoZombie> requestSnapshot = this.createRequestSnapshot(connection);
        List<Short> sent = this.zombiesToSend.computeIfAbsent(connection, ignored -> new ArrayList<>());
        sent.clear();
        return this.getZombieData(connection, packet, requestSnapshot, sent);
    }

    public void send(UdpConnection connection) {
        this.send(connection, this.createRequestSnapshot(connection), false);
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

            if (zombie.getOwner() != null
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
            if (zombie.getOwner() != null && zombie.onlineId != -1) {
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

    private void send(UdpConnection connection, Collection<IsoZombie> requestSnapshot, boolean forceExtraUpdate) {
        if (!connection.isFullyConnected() || GameServer.isDelayedDisconnect(connection)) {
            return;
        }

        if (!this.zombiesDeletedForSending.isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.ZombieDeleteOnClient, connection, this.zombiesDeletedForSending);
        }

        ZombieListPacket listPacket = (ZombieListPacket)connection.getPacket(PacketTypes.PacketType.ZombieList);
        int newHash = NetworkZombieManager.getInstance().getZombieAuth(connection, listPacket);
        boolean hashChanged = connection.getZombieListHash() != newHash;
        boolean overdue = !listPacket.zombiesAuth.isEmpty() && connection.zombieListRefresh.Check();
        List<Short> zombiesToSend = new ArrayList<>();
        if (hashChanged || overdue) {
            connection.setZombieListHash(newHash);
            connection.zombieListRefresh.Reset();
            ByteBufferWriter writer = connection.startPacket();
            PacketTypes.PacketType.ZombieList.doPacket(writer);
            listPacket.write(writer);
            PacketTypes.PacketType.ZombieList.send(connection);
            if (hashChanged) {
                NetworkZombieList.NetworkZombie deferredRequests = this.zombiesRequest.getNetworkZombie(connection);
                for (Short zombieId : listPacket.zombiesAuth) {
                    IsoZombie zombie = ServerMap.instance.zombieMap.get(zombieId);
                    if (zombie != null && zombie.onlineId != -1 && !requestSnapshot.contains(zombie)) {
                        if (requestSnapshot.size() < 300) {
                            requestSnapshot.add(zombie);
                        } else if (!deferredRequests.zombies.contains(zombie)) {
                            deferredRequests.zombies.add(zombie);
                        }
                    }
                }
            }
        }

        ZombieSynchronizationPacket syncPacket = (ZombieSynchronizationPacket)connection.getPacket(PacketTypes.PacketType.ZombieSynchronizationReliable);
        syncPacket.hasNeighborPlayer = connection.isNeighborPlayer();
        int count = this.getZombieData(connection, syncPacket, requestSnapshot, zombiesToSend);
        if (count > 0 || connection.timerSendZombie.check() || forceExtraUpdate || this.extraUpdate.contains(connection)) {
            this.extraUpdate.remove(connection);
            connection.timerSendZombie.reset(3800L);
            ByteBufferWriter writer = connection.startPacket();
            PacketTypes.PacketType packetType;
            synchronized (this.zombieSynchronizationReliableLimit) {
                packetType = this.zombieSynchronizationReliableLimit.Check()
                    ? PacketTypes.PacketType.ZombieSynchronizationReliable
                    : PacketTypes.PacketType.ZombieSynchronizationUnreliable;
            }

            packetType.doPacket(writer);
            syncPacket.write(writer);
            packetType.send(connection);
        }

        this.zombiesToSend.put(connection, zombiesToSend);
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

    private class ConnectionJob implements Runnable {
        private final UdpConnection connection;
        private final Collection<IsoZombie> requestSnapshot;
        private final boolean forceExtraUpdate;
        private final CountDownLatch latch;

        ConnectionJob(UdpConnection connection, Collection<IsoZombie> requestSnapshot, boolean forceExtraUpdate, CountDownLatch latch) {
            this.connection = connection;
            this.requestSnapshot = requestSnapshot;
            this.forceExtraUpdate = forceExtraUpdate;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                HashSet<IsoZombie> relayCandidates = new HashSet<>();
                NetworkZombiePacker.this.getRelayCandidates(this.connection, relayCandidates);
                for (IsoZombie zombie : relayCandidates) {
                    if (this.requestSnapshot.size() >= 300) {
                        break;
                    }

                    if (zombie.getOwner() != null
                        && zombie.getOwner() != this.connection
                        && this.connection.RelevantTo(zombie.getX(), zombie.getY(), (this.connection.getRelevantRange() - 2) * 10)
                        && zombie.onlineId != -1) {
                        this.requestSnapshot.add(zombie);
                    }
                }

                NetworkZombiePacker.this.completedJobs.offer(
                    new NetworkZombiePacker.ConnectionResult(this.connection, this.requestSnapshot, this.forceExtraUpdate)
                );
            } catch (Exception exception) {
                DebugType.General.printException(exception, LogSeverity.Error);
            } finally {
                this.latch.countDown();
            }
        }
    }

    private static final class ConnectionResult {
        final UdpConnection connection;
        final Collection<IsoZombie> requestSnapshot;
        final boolean forceExtraUpdate;

        ConnectionResult(UdpConnection connection, Collection<IsoZombie> requestSnapshot, boolean forceExtraUpdate) {
            this.connection = connection;
            this.requestSnapshot = requestSnapshot;
            this.forceExtraUpdate = forceExtraUpdate;
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
