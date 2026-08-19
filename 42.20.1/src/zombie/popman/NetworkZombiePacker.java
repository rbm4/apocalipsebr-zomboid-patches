// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import zombie.ApocBRServerTelemetry;
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
    private static final NetworkZombiePacker instance = new NetworkZombiePacker();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeleted = new ArrayList<>();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeletedForSending = new ArrayList<>();
    private final HashSet<IsoZombie> zombiesReceived = new HashSet<>();
    private final ArrayList<IsoZombie> zombiesProcessing = new ArrayList<>();
    private final Map<Long, ArrayList<IsoZombie>> zombiesProcessingByCell = new HashMap<>();
    private final HashSet<IsoZombie> relayCandidateScratch = new HashSet<>();
    private int relayCellsVisited;
    public final NetworkZombieList zombiesRequest = new NetworkZombieList();
    private final ZombiePacket packet = new ZombiePacket();
    private final HashSet<IConnection> extraUpdate = new HashSet<>();
    private boolean extraUpdateAll;
    public final Map<IConnection, List<Short>> zombiesToSend = new HashMap<>();
    UpdateLimit zombieSynchronizationReliableLimit = new UpdateLimit(5000L);
    private static final int RELAY_GRID_CELL_SIZE = 64;

    public static NetworkZombiePacker getInstance() {
        return instance;
    }

    public void setExtraUpdate() {
        this.extraUpdateAll = true;
        ApocBRServerTelemetry.recordZombieRelayExtraAllMark();
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

    public void postupdate() {
        long apocBrPostStart = System.nanoTime();
        this.updateAuth();
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

        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connection != null && connection.isFullyConnected()) {
                long apocBrConnectionStart = System.nanoTime();
                ZombieListPacket packet = (ZombieListPacket)connection.getPacket(PacketTypes.PacketType.ZombieList);
                long apocBrAuthListStart = System.nanoTime();
                int newHash = NetworkZombieManager.getInstance().getZombieAuth(connection, packet);
                ApocBRServerTelemetry.recordZombieAuthList(System.nanoTime() - apocBrAuthListStart);
                boolean hashChanged = connection.getZombieListHash() != newHash;
                boolean overdue = !packet.zombiesAuth.isEmpty() && connection.zombieListRefresh.Check();
                this.zombiesToSend.computeIfAbsent(connection, k -> new ArrayList<>()).clear();
                this.zombiesToSend.get(connection).addAll(packet.zombiesAuth);
                if (hashChanged || overdue) {
                    connection.setZombieListHash(newHash);
                    connection.zombieListRefresh.Reset();
                    ByteBufferWriter b = connection.startPacket();
                    PacketTypes.PacketType.ZombieList.doPacket(b);
                    packet.write(b);
                    PacketTypes.PacketType.ZombieList.send(connection);
                    if (hashChanged) {
                        NetworkZombieList.NetworkZombie netZombieRequest = this.zombiesRequest.getNetworkZombie(connection);

                        for (Short zombieId : packet.zombiesAuth) {
                            IsoZombie z = ServerMap.instance.zombieMap.get(zombieId);
                            if (z != null && z.onlineId != -1 && !netZombieRequest.zombies.contains(z)) {
                                netZombieRequest.zombies.add(z);
                            }
                        }
                    }
                }

                this.send(connection);
                ApocBRServerTelemetry.recordZombieRelayConnection(System.nanoTime() - apocBrConnectionStart);
            }
        }

        this.extraUpdateAll = false;
        ApocBRServerTelemetry.recordZombieRelayPost(System.nanoTime() - apocBrPostStart);
    }

    private void updateAuth() {
        long apocBrAuthStart = System.nanoTime();
        ArrayList<IsoZombie> zl = IsoWorld.instance.currentCell.getZombieList();
        NetworkZombieManager.getInstance().beginAuthUpdate();

        for (int i = 0; i < zl.size(); i++) {
            IsoZombie z = zl.get(i);
            NetworkZombieManager.getInstance().updateAuth(z);
        }
        ApocBRServerTelemetry.recordZombieAuthUpdate(zl.size(), System.nanoTime() - apocBrAuthStart);
    }

    public int getZombieData(UdpConnection connection, ZombieSynchronizationPacket packet) {
        packet.sendQueue.clear();
        int realCount = 0;
        int initialSent = 0;
        int relaySent = 0;

        try {
            NetworkZombieList.NetworkZombie nzr = this.zombiesRequest.getNetworkZombie(connection);

            while (!nzr.zombies.isEmpty()) {
                IsoZombie z = nzr.zombies.poll();
                z.zombiePacket.set(z);
                if (z.onlineId != -1) {
                    packet.sendQueue.add(z);
                    z.zombiePacketUpdated = false;
                    initialSent++;
                    if (++realCount >= 300) {
                        break;
                    }
                }
            }

            HashSet<IsoZombie> relayCandidates = this.getRelayCandidates(connection);
            for (IsoZombie z : relayCandidates) {
                if (z.getOwner() != null
                    && z.getOwner() != connection
                    && connection.RelevantTo(z.getX(), z.getY(), (connection.getRelevantRange() - 2) * 10)
                    && z.onlineId != -1) {
                    packet.sendQueue.add(z);
                    this.zombiesToSend.get(connection).add(z.getOnlineID());
                    z.zombiePacketUpdated = false;
                    realCount++;
                    relaySent++;
                }
            }
            ApocBRServerTelemetry.recordZombieRelayInitial(initialSent);
            ApocBRServerTelemetry.recordZombieRelayQuery(this.relayCellsVisited, relayCandidates.size(), relaySent);
        } catch (BufferOverflowException var7) {
            DebugType.General.printException(var7, LogSeverity.Error);
        }

        return realCount;
    }

    private void rebuildZombiesProcessingGrid() {
        long startNanos = System.nanoTime();
        this.zombiesProcessingByCell.clear();
        int active = 0;

        for (int i = 0; i < this.zombiesProcessing.size(); i++) {
            IsoZombie z = this.zombiesProcessing.get(i);
            if (z.getOwner() != null && z.onlineId != -1) {
                active++;
                this.zombiesProcessingByCell.computeIfAbsent(key(cellFor(z.getX()), cellFor(z.getY())), ignored -> new ArrayList<>()).add(z);
            }
        }

        ApocBRServerTelemetry.recordZombieRelayGrid(active, this.zombiesProcessingByCell.size(), System.nanoTime() - startNanos);
    }

    private HashSet<IsoZombie> getRelayCandidates(UdpConnection connection) {
        this.relayCandidateScratch.clear();
        this.relayCellsVisited = 0;
        int radius = (connection.getRelevantRange() - 2) * 10;

        for (IsoPlayer player : connection.players) {
            if (player != null && player.isAlive()) {
                int minCellX = cellFor(player.getX() - radius);
                int maxCellX = cellFor(player.getX() + radius);
                int minCellY = cellFor(player.getY() - radius);
                int maxCellY = cellFor(player.getY() + radius);
                this.addRelayCells(minCellX, maxCellX, minCellY, maxCellY);
            }
        }

        for (int n = 0; n < connection.connectArea.length; n++) {
            if (connection.connectArea[n] != null) {
                int chunkMapWidth = (int)connection.connectArea[n].z;
                int minX = PZMath.fastfloor(connection.connectArea[n].x - chunkMapWidth / 2) * 8;
                int minY = PZMath.fastfloor(connection.connectArea[n].y - chunkMapWidth / 2) * 8;
                int maxX = minX + chunkMapWidth * 8;
                int maxY = minY + chunkMapWidth * 8;
                this.addRelayCells(cellFor(minX), cellFor(maxX), cellFor(minY), cellFor(maxY));
            }
        }

        return this.relayCandidateScratch;
    }

    private void addRelayCells(int minCellX, int maxCellX, int minCellY, int maxCellY) {
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cy = minCellY; cy <= maxCellY; cy++) {
                this.relayCellsVisited++;
                ArrayList<IsoZombie> zombies = this.zombiesProcessingByCell.get(key(cx, cy));
                if (zombies != null) {
                    this.relayCandidateScratch.addAll(zombies);
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

    public void send(UdpConnection connection) {
        long apocBrSendStart = System.nanoTime();
        if (!this.zombiesDeletedForSending.isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.ZombieDeleteOnClient, connection, this.zombiesDeletedForSending);
        }

        ZombieSynchronizationPacket packet = (ZombieSynchronizationPacket)connection.getPacket(PacketTypes.PacketType.ZombieSynchronizationReliable);
        packet.hasNeighborPlayer = connection.isNeighborPlayer();
        long apocBrGetDataStart = System.nanoTime();
        int countData = this.getZombieData(connection, packet);
        ApocBRServerTelemetry.recordZombieRelayGetData(System.nanoTime() - apocBrGetDataStart);
        if (countData > 0 || connection.timerSendZombie.check() || this.extraUpdateAll || this.extraUpdate.contains(connection)) {
            ApocBRServerTelemetry.recordZombieRelayPacket(this.extraUpdateAll);
            this.extraUpdate.remove(connection);
            connection.timerSendZombie.reset(3800L);
            ByteBufferWriter b = connection.startPacket();
            PacketTypes.PacketType packetType;
            if (this.zombieSynchronizationReliableLimit.Check()) {
                packetType = PacketTypes.PacketType.ZombieSynchronizationReliable;
            } else {
                packetType = PacketTypes.PacketType.ZombieSynchronizationUnreliable;
            }

            packetType.doPacket(b);
            packet.write(b);
            packetType.send(connection);
        }
        ApocBRServerTelemetry.recordZombieRelaySend(System.nanoTime() - apocBrSendStart);
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
