// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public final NetworkZombieList zombiesRequest = new NetworkZombieList();
    private final ZombiePacket packet = new ZombiePacket();
    private final HashSet<IConnection> extraUpdate = new HashSet<>();
    private final Map<Short, NetworkZombiePacker.ZombiePacketProbe> packetProbe = new HashMap<>();
    public final Map<IConnection, List<Short>> zombiesToSend = new HashMap<>();
    UpdateLimit zombieSynchronizationReliableLimit = new UpdateLimit(5000L);

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

        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if (connection != null && connection.isFullyConnected()) {
                ZombieListPacket packet = (ZombieListPacket)connection.getPacket(PacketTypes.PacketType.ZombieList);
                int newHash = NetworkZombieManager.getInstance().getZombieAuth(connection, packet);
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
            }
        }
    }

    private void updateAuth() {
        ArrayList<IsoZombie> zl = IsoWorld.instance.currentCell.getZombieList();

        for (int i = 0; i < zl.size(); i++) {
            IsoZombie z = zl.get(i);
            NetworkZombieManager.getInstance().updateAuth(z);
        }
    }

    public int getZombieData(UdpConnection connection, ZombieSynchronizationPacket packet, boolean fullRefresh) {
        packet.sendQueue.clear();
        int realCount = 0;

        try {
            NetworkZombieList.NetworkZombie nzr = this.zombiesRequest.getNetworkZombie(connection);

            while (!nzr.zombies.isEmpty()) {
                IsoZombie z = nzr.zombies.poll();
                z.zombiePacket.set(z);
                this.probeZombiePacket(z);
                if (z.onlineId != -1) {
                    packet.sendQueue.add(z);
                    z.zombiePacketUpdated = false;
                    if (++realCount >= 300) {
                        break;
                    }
                }
            }

            if (fullRefresh) {
                ArrayList<IsoZombie> zl = IsoWorld.instance.currentCell.getZombieList();
                for (int k = 0; k < zl.size(); k++) {
                    IsoZombie z = zl.get(k);
                    if (z.onlineId != -1 && connection.RelevantTo(z.getX(), z.getY(), (connection.getRelevantRange() - 2) * 10)) {
                        z.zombiePacket.set(z);
                        this.probeZombiePacket(z);
                        packet.sendQueue.add(z);
                        this.zombiesToSend.get(connection).add(z.getOnlineID());
                        z.zombiePacketUpdated = false;
                        if (++realCount >= 300) {
                            break;
                        }
                    }
                }
            }
        } catch (BufferOverflowException var7) {
            DebugType.General.printException(var7, LogSeverity.Error);
        }

        return realCount;
    }

    private void probeZombiePacket(IsoZombie z) {
        if (z.onlineId == -1) {
            return;
        }

        ZombiePacket packet = z.zombiePacket;
        NetworkZombiePacker.ZombiePacketProbe probe = this.packetProbe.computeIfAbsent(
            z.onlineId,
            id -> new NetworkZombiePacker.ZombiePacketProbe(packet.realX, packet.realY)
        );
        float realDx = packet.realX - probe.lastRealX;
        float realDy = packet.realY - probe.lastRealY;
        float realDeltaSq = realDx * realDx + realDy * realDy;
        float targetDx = packet.x - packet.realX;
        float targetDy = packet.y - packet.realY;
        float targetDeltaSq = targetDx * targetDx + targetDy * targetDy;
        long now = System.currentTimeMillis();
        if (packet.predictionType == 0 && realDeltaSq > 1.0E-4F && now - probe.lastLogTime > 1000L) {
            probe.lastLogTime = now;
            DebugType.Multiplayer.error(
                "ApocBR zombie packet probe id=%d pred=%d real=(%.3f,%.3f) last=(%.3f,%.3f) target=(%.3f,%.3f) dReal=%.4f dTarget=%.4f realState=%s moving=%s bMoving=%s bPathfind=%s state=%s",
                z.onlineId,
                packet.predictionType,
                packet.realX,
                packet.realY,
                probe.lastRealX,
                probe.lastRealY,
                packet.x,
                packet.y,
                realDeltaSq,
                targetDeltaSq,
                packet.realState,
                z.isMoving(),
                z.getVariableBoolean("bMoving"),
                z.getVariableBoolean("bPathfind"),
                z.getCurrentState() == null ? "null" : z.getCurrentState().getClass().getSimpleName()
            );
        }

        probe.lastRealX = packet.realX;
        probe.lastRealY = packet.realY;
    }

    public void send(UdpConnection connection) {
        if (!this.zombiesDeletedForSending.isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.ZombieDeleteOnClient, connection, this.zombiesDeletedForSending);
        }

        ZombieSynchronizationPacket packet = (ZombieSynchronizationPacket)connection.getPacket(PacketTypes.PacketType.ZombieSynchronizationReliable);
        packet.hasNeighborPlayer = false;
        boolean fullRefresh = connection.timerSendZombie.check() || this.extraUpdate.contains(connection);
        int countData = this.getZombieData(connection, packet, fullRefresh);
        if (countData > 0 || fullRefresh) {
            this.extraUpdate.remove(connection);
            connection.timerSendZombie.reset(200L);
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

    private static final class ZombiePacketProbe {
        float lastRealX;
        float lastRealY;
        long lastLogTime;

        ZombiePacketProbe(float realX, float realY) {
            this.lastRealX = realX;
            this.lastRealY = realY;
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
