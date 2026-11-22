// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerAI;
import zombie.characters.Roles;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector3;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheatNoClip;
import zombie.network.anticheats.AntiCheatPower;
import zombie.network.anticheats.AntiCheatSpeed;
import zombie.network.fields.IMovable;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.character.PlayerVariables;
import zombie.network.fields.character.Prediction;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.network.statistics.data.GameStatistic;
import zombie.pathfind.PolygonalMap2;
import zombie.popman.NetworkZombieSimulator;

public class PlayerPacket implements INetworkPacket, AntiCheatPower.IAntiCheat, AntiCheatSpeed.IAntiCheat, AntiCheatNoClip.IAntiCheat {
    public static final int PACKET_SIZE_BYTES = 21;
    @JSONField
    public final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    public final Prediction prediction = new Prediction();
    @JSONField
    public short booleanVariables;
    @JSONField
    public boolean disconnected;
    @JSONField
    public final VehicleID hitVehicleId = new VehicleID();
    public final PlayerVariables variables = new PlayerVariables();

    @Override
    public int getPacketSizeBytes() {
        return 21;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.prediction.parse(b, connection);
        this.booleanVariables = b.getShort();
        this.variables.parse(b, connection);
        this.disconnected = b.getBoolean();
        this.hitVehicleId.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        this.prediction.write(b);
        b.putShort(this.booleanVariables);
        this.variables.write(b);
        b.putBoolean(this.disconnected);
        this.hitVehicleId.write(b);
    }

    public PacketTypes.PacketType set(IsoPlayer chr) {
        this.player.set(chr);
        this.variables.set(chr);
        return chr.getNetworkCharacterAI().set(this);
    }

    public static void sendToRelative(IsoPlayer player) {
        Long playerGuid = GameServer.PlayerToAddressMap.get(player);
        NetworkPlayerAI networkAi = player.getNetworkCharacterAI();
        PlayerPacket packet = networkAi.getPlayerPacket();
        networkAi.needToUpdate();
        packet.set(player);

        for (UdpConnection connection : GameServer.udpEngine.connections) {
            if ((playerGuid == null || connection.getConnectedGUID() != playerGuid)
                && connection.isFullyConnected()
                && player.checkCanSeeClient(connection)
                && connection.isRelevantTo(player.getX(), player.getY())) {
                ByteBufferWriter bbw = connection.startPacket();
                PacketTypes.PacketType.PlayerUpdateReliable.doPacket(bbw);
                packet.write(bbw);
                PacketTypes.PacketType.PlayerUpdateReliable.send(connection);
            }
        }
    }

    private static int getFloorZ(float x, float y, int z) {
        IsoChunk chunk = IsoCell.getInstance().getChunkForGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), z);
        if (chunk == null) {
            return z;
        } else {
            for (int zi = z; zi >= chunk.minLevel; zi--) {
                IsoGridSquare square = IsoCell.getInstance().getGridSquare((double)x, (double)y, (double)zi);
                if (square != null && square.TreatAsSolidFloor()) {
                    return zi;
                }
            }

            return chunk.minLevel;
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        try {
            IsoPlayer isoPlayer = this.player.getPlayer();
            if (isoPlayer == null) {
                INetworkPacket.send(PacketTypes.PacketType.PlayerDataRequest, this.player.getId());
            } else {
                GameClient.rememberPlayerPosition(isoPlayer, this.prediction.position.x, this.prediction.position.y);
                NetworkPlayerAI networkAi = isoPlayer.getNetworkCharacterAI();
                if (!networkAi.isHitByVehicle()) {
                    networkAi.parse(this);
                }

                networkAi.disconnected = this.disconnected;
                if (this.disconnected) {
                    NetworkZombieSimulator.getInstance().clearTargetAuth(isoPlayer);
                }

                if (isoPlayer.getVehicle() == null
                    && 2 != this.prediction.type
                    && (
                        networkAi.distance.getLength() > 7.0F
                            || IsoUtils.DistanceTo(
                                        this.prediction.x, this.prediction.y, this.prediction.z, isoPlayer.getX(), isoPlayer.getY(), isoPlayer.getZ()
                                    )
                                    > 1.0F
                                && !PolygonalMap2.instance
                                    .lineClearCollide(isoPlayer.getX(), isoPlayer.getY(), this.prediction.x, this.prediction.y, isoPlayer.getZi())
                                && (
                                    this.prediction.z > isoPlayer.getZi()
                                        || this.prediction.z < getFloorZ(this.prediction.x, this.prediction.y, isoPlayer.getZi())
                                )
                    )
                    && (isoPlayer.getCurrentState() == null || !isoPlayer.getCurrentState().isSyncOnSquare())) {
                    isoPlayer.teleportTo(this.prediction.x, this.prediction.y, this.prediction.z);
                }

                IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare((double)this.prediction.x, (double)this.prediction.y, (double)this.prediction.z);
                if (sq != null) {
                    boolean hasObstacle = isoPlayer.getSquare() != null
                        && (isoPlayer.isCollidedThisFrame() || isoPlayer.isCollidedWithVehicle())
                        && (
                            IsoUtils.DistanceTo(
                                        this.prediction.position.x,
                                        this.prediction.position.y,
                                        this.prediction.position.z,
                                        isoPlayer.getX(),
                                        isoPlayer.getY(),
                                        isoPlayer.getZ()
                                    )
                                    > 1.0F
                                || PolygonalMap2.instance
                                    .lineClearCollide(
                                        isoPlayer.getX(), isoPlayer.getY(), this.prediction.x, this.prediction.y, PZMath.fastfloor(this.prediction.z)
                                    )
                        );
                    isoPlayer.setHasObstacleOnPath(hasObstacle);
                    if (isoPlayer.isAlive() && !IsoWorld.instance.currentCell.getObjectList().contains(isoPlayer)) {
                        IsoWorld.instance.currentCell.getObjectList().add(isoPlayer);
                        isoPlayer.setCurrent(sq);
                        networkAi.resetState();
                        INetworkPacket.send(PacketTypes.PacketType.PlayerDataRequest, this.player.getId());
                    }
                } else if (IsoWorld.instance.currentCell.getObjectList().contains(isoPlayer)) {
                    isoPlayer.removeFromWorld();
                    isoPlayer.removeFromSquare();
                }
            }
        } catch (Exception var6) {
            DebugType.Multiplayer.printException(var6, "Can't process PlayerPacket", LogSeverity.Error);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        connection.getValidator().playerUpdateTimeoutReset();
        IsoPlayer isoPlayer = this.player.getPlayer();

        try {
            NetworkPlayerAI networkAi = isoPlayer.getNetworkCharacterAI();
            if (!networkAi.isHitByVehicle()) {
                networkAi.parse(this);
            }

            if (networkAi.distance.getLength() > IsoChunkMap.chunkWidthInTiles) {
                GameStatistic.getInstance().playersTeleports.increase();
            }

            connection.releventPos[isoPlayer.playerIndex].set(this.prediction.position);
            isoPlayer.setLastRemoteUpdate(System.currentTimeMillis());
        } catch (Exception var7) {
            DebugType.Multiplayer.printException(var7, "Can't process PlayerPacket", LogSeverity.Error);
        }

        for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
            UdpConnection c = GameServer.udpEngine.connections.get(n);
            if (connection.getConnectedGUID() != c.getConnectedGUID()
                && c.isFullyConnected()
                && (
                    isoPlayer.checkCanSeeClient(c) && c.isRelevantTo(this.prediction.x, this.prediction.y)
                        || packetType == PacketTypes.PacketType.PlayerUpdateReliable
                            && (c.getRole().getPosition() > connection.getRole().getPosition() || c.getRole() == Roles.getDefaultForAdmin())
                )) {
                ByteBufferWriter bbw = c.startPacket();
                packetType.doPacket(bbw);
                this.write(bbw);
                packetType.send(c);
                GameServer.setCustomVariables(isoPlayer, c);
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        return isoPlayer == null ? !GameServer.server : isoPlayer.getVehicle() == null;
    }

    @Override
    public short getBooleanVariables() {
        return this.booleanVariables;
    }

    @Override
    public IsoPlayer getPlayer() {
        return this.player.getPlayer();
    }

    @Override
    public IMovable getMovable(int index) {
        return this.getPlayer().getNetworkCharacterAI().speedChecker;
    }

    @Override
    public int getMovableCount() {
        return 1;
    }

    @Override
    public byte getPlayerIndex() {
        return (byte)this.getPlayer().getIndex();
    }

    @Override
    public Vector3 getPosition(Vector3 position) {
        return position.set(this.prediction.position);
    }

    @Override
    public boolean validateHitVehicleDistance() {
        return this.hitVehicleId.getVehicle() != null
            && this.hitVehicleId.getVehicle().validateHitVehicleDistance(this.prediction.position.x, this.prediction.position.y);
    }

    @Override
    public void resetMovable() {
        if (!this.getPlayer().isDead()) {
            this.getPlayer()
                .getNetworkCharacterAI()
                .speedChecker
                .set(this.prediction.position.x, this.prediction.position.y, this.getPlayer().isSeatedInVehicle(), this.hitVehicleId.getVehicle());
        }
    }
}
