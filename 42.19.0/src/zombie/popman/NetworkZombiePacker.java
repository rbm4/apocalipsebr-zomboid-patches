// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.ApocBRServerTelemetry;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
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
    private static final long APOCBR_AUTH_SWEEP_MS = Math.max(100L, Math.min(2000L, Long.getLong("apocbr.zombieAuthSweepMs", 500L)));
    private static final boolean APOCBR_AUTH_TIERING = Boolean.parseBoolean(System.getProperty("apocbr.zombieAuthTiering", "true"));
    private static final NetworkZombiePacker instance = new NetworkZombiePacker();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeleted = new ArrayList<>();
    private final ArrayList<NetworkZombiePacker.DeletedZombie> zombiesDeletedForSending = new ArrayList<>();
    private final HashSet<IsoZombie> zombiesReceived = new HashSet<>();
    private final ArrayList<IsoZombie> zombiesProcessing = new ArrayList<>();
    public final NetworkZombieList zombiesRequest = new NetworkZombieList();
    private final ZombiePacket packet = new ZombiePacket();
    private final HashSet<IConnection> extraUpdate = new HashSet<>();
    UpdateLimit zombieSynchronizationReliableLimit = new UpdateLimit(5000L);
    private int lastAuthScanned;
    private int lastAuthOwnerChanges;
    private int lastAuthUnowned;
    private int lastZombieListPackets;
    private int lastSyncPackets;
    private int lastSyncZombies;
    private int lastDeletePackets;
    private long lastHashNanos;
    private long lastSendNanos;
    private int apocBrAuthCursor;
    private long apocBrLastAuthSweepMs;
    private int lastAuthLive;
    private int lastAuthUrgent;
    private int lastAuthDeferred;

    public static NetworkZombiePacker getInstance() {
        return instance;
    }

    public void setExtraUpdate() {
        for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
            UdpConnection c = GameServer.udpEngine.connections.get(n);
            if (c.isFullyConnected()) {
                this.extraUpdate.add(c);
            }
        }
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
        this.updateAuth();
        synchronized (this.zombiesReceived) {
            this.zombiesProcessing.clear();
            this.zombiesProcessing.addAll(this.zombiesReceived);
            this.zombiesReceived.clear();
        }

        synchronized (this.zombiesDeleted) {
            this.zombiesDeletedForSending.clear();
            this.zombiesDeletedForSending.addAll(this.zombiesDeleted);
            this.zombiesDeleted.clear();
        }

        this.lastZombieListPackets = 0;
        this.lastSyncPackets = 0;
        this.lastSyncZombies = 0;
        this.lastDeletePackets = 0;
        this.lastHashNanos = 0L;
        this.lastSendNanos = 0L;
        int apocBrConnections = 0;
        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connection != null && connection.isFullyConnected()) {
                apocBrConnections++;
                ZombieListPacket packet = (ZombieListPacket)connection.getPacket(PacketTypes.PacketType.ZombieList);
                long apocBrHashStart = System.nanoTime();
                int newHash = NetworkZombieManager.getInstance().getZombieAuth(connection, packet);
                this.lastHashNanos += System.nanoTime() - apocBrHashStart;
                boolean hashChanged = connection.getZombieListHash() != newHash;
                boolean overdue = !packet.zombiesAuth.isEmpty() && connection.zombieListRefresh.Check();
                if (hashChanged || overdue) {
                    connection.setZombieListHash(newHash);
                    connection.zombieListRefresh.Reset();
                    ByteBufferWriter b = connection.startPacket();
                    PacketTypes.PacketType.ZombieList.doPacket(b);
                    packet.write(b);
                    PacketTypes.PacketType.ZombieList.send(connection);
                    this.lastZombieListPackets++;
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
            }
        }
        ApocBRServerTelemetry.recordZombieNetworkBreakdown(
            this.lastAuthLive, this.lastAuthScanned, this.lastAuthUrgent, this.lastAuthDeferred,
            this.lastAuthOwnerChanges, this.lastAuthUnowned, apocBrConnections,
            this.lastHashNanos, this.lastZombieListPackets, this.lastSendNanos, this.lastSyncPackets,
            this.lastSyncZombies, this.lastDeletePackets
        );
    }

    private void updateAuth() {
        ArrayList<IsoZombie> zl = IsoWorld.instance.currentCell.getZombieList();
        this.lastAuthLive = zl.size();
        this.lastAuthScanned = 0;
        this.lastAuthOwnerChanges = 0;
        this.lastAuthUnowned = 0;
        this.lastAuthUrgent = 0;
        this.lastAuthDeferred = 0;

        if (!GameServer.server || zl.isEmpty()) {
            return;
        }

        if (!APOCBR_AUTH_TIERING) {
            for (int i = 0; i < zl.size(); i++) {
                IsoZombie z = zl.get(i);
                if (z.getOwner() == null) this.lastAuthUnowned++;
                this.updateAuthZombie(z, false);
            }

            return;
        }

        // Combat and player-contact zombies keep the vanilla per-frame cadence.
        // All other zombies are rechecked in a time-based round robin.  This
        // avoids frame-count starvation when the server is already under load.
        for (int i = 0; i < zl.size(); i++) {
            IsoZombie z = zl.get(i);
            if (z.getOwner() == null) {
                this.lastAuthUnowned++;
            }
            if (this.isAuthUrgent(z)) {
                this.updateAuthZombie(z, true);
            }
        }

        long now = System.currentTimeMillis();
        long elapsed = this.apocBrLastAuthSweepMs == 0L ? APOCBR_AUTH_SWEEP_MS : now - this.apocBrLastAuthSweepMs;
        this.apocBrLastAuthSweepMs = now;
        int budget = (int)Math.min(
            zl.size(), Math.max(1L, ((long)zl.size() * Math.max(1L, elapsed) + APOCBR_AUTH_SWEEP_MS - 1L) / APOCBR_AUTH_SWEEP_MS)
        );
        int visited = 0;

        while (visited < zl.size() && this.lastAuthDeferred < budget) {
            if (this.apocBrAuthCursor >= zl.size()) {
                this.apocBrAuthCursor = 0;
            }

            IsoZombie z = zl.get(this.apocBrAuthCursor++);
            visited++;
            if (!this.isAuthUrgent(z)) {
                this.updateAuthZombie(z, false);
            }
        }
    }

    private boolean isAuthUrgent(IsoZombie zombie) {
        return zombie.getTarget() != null || zombie.getWrappedGrappleable().getGrappledBy() instanceof IsoPlayer;
    }

    private void updateAuthZombie(IsoZombie zombie, boolean urgent) {
        IConnection previousOwner = zombie.getOwner();
        NetworkZombieManager.getInstance().updateAuth(zombie);
        this.lastAuthScanned++;
        if (urgent) this.lastAuthUrgent++; else this.lastAuthDeferred++;
        if (previousOwner != zombie.getOwner()) this.lastAuthOwnerChanges++;
    }

    public int getZombieData(UdpConnection connection, ZombieSynchronizationPacket packet) {
        packet.sendQueue.clear();
        int realCount = 0;

        try {
            NetworkZombieList.NetworkZombie nzr = this.zombiesRequest.getNetworkZombie(connection);

            while (!nzr.zombies.isEmpty()) {
                IsoZombie z = nzr.zombies.poll();
                z.zombiePacket.set(z);
                if (z.onlineId != -1) {
                    packet.sendQueue.add(z);
                    z.zombiePacketUpdated = false;
                    if (++realCount >= 300) {
                        break;
                    }
                }
            }

            for (int k = 0; k < this.zombiesProcessing.size(); k++) {
                IsoZombie z = this.zombiesProcessing.get(k);
                if (z.getOwner() != null
                    && z.getOwner() != connection
                    && connection.RelevantTo(z.getX(), z.getY(), (connection.getRelevantRange() - 2) * 10)
                    && z.onlineId != -1) {
                    packet.sendQueue.add(z);
                    z.zombiePacketUpdated = false;
                    realCount++;
                }
            }
        } catch (BufferOverflowException var7) {
            DebugType.General.printException(var7, LogSeverity.Error);
        }

        return realCount;
    }

    public void send(UdpConnection connection) {
        long apocBrSendStart = System.nanoTime();
        if (!this.zombiesDeletedForSending.isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.ZombieDeleteOnClient, connection, this.zombiesDeletedForSending);
            this.lastDeletePackets++;
        }

        ZombieSynchronizationPacket packet = (ZombieSynchronizationPacket)connection.getPacket(PacketTypes.PacketType.ZombieSynchronizationReliable);
        packet.hasNeighborPlayer = connection.isNeighborPlayer();
        int countData = this.getZombieData(connection, packet);
        if (countData > 0 || connection.timerSendZombie.check() || this.extraUpdate.contains(connection)) {
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
            this.lastSyncPackets++;
            this.lastSyncZombies += countData;
        }
        this.lastSendNanos += System.nanoTime() - apocBrSendStart;
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

        zombie.networkAi.targetX = this.packet.x;
        zombie.networkAi.targetY = this.packet.y;
        zombie.networkAi.targetZ = this.packet.z;
        zombie.networkAi.predictionType = this.packet.predictionType;
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
