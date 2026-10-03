// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import zombie.ai.State;
import zombie.ApocBRServerTelemetryLite;
import zombie.ai.states.GenericDefaultState;
import zombie.ai.states.ZombieEatBodyState;
import zombie.ai.states.ZombieIdleState;
import zombie.ai.states.ZombieSittingState;
import zombie.ai.states.ZombieTurnAlerted;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.NetworkVariables;
import zombie.network.ServerOptions;
import zombie.network.packets.character.ZombieListPacket;
import zombie.util.hash.PZHash;

public class NetworkZombieManager {
    private static final NetworkZombieManager instance = new NetworkZombieManager();
    private final ZombieOwnershipIndex owns = new ZombieOwnershipIndex();
    private static final float NospottedDistanceSquared = 16.0F;
    private final ZombieAuthCoverage authCoverage = new ZombieAuthCoverage();
    private final IdentityHashMap<IsoZombie, AuthState> authStates = new IdentityHashMap<>();
    private long lastStateCleanup;
    private boolean authGridBuilt;

    private static final class AuthState {
        UdpConnection owner;
        Object target, grapple;
        short onlineId;
        long nextCheck;
    }

    public static NetworkZombieManager getInstance() {
        return instance;
    }

    public int getAuthorizedZombieCount(UdpConnection con) {
        return (int)IsoWorld.instance.currentCell.getZombieList().stream().filter(z -> z.getOwner() == con).count();
    }

    public int getUnauthorizedZombieCount() {
        return (int)IsoWorld.instance.currentCell.getZombieList().stream().filter(z -> z.getOwner() == null).count();
    }

    public static boolean canSpotted(IsoZombie zombie) {
        if (zombie.isRemoteZombie()) {
            return false;
        } else if (zombie.target != null && IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), zombie.target.getX(), zombie.target.getY()) < 16.0F) {
            return false;
        } else {
            State state = zombie.getCurrentState();
            return state == null
                || state == ZombieIdleState.instance()
                || state == ZombieEatBodyState.instance()
                || state == ZombieSittingState.instance()
                || state == ZombieTurnAlerted.instance()
                || state == GenericDefaultState.instance();
        }
    }

    public void beginAuthUpdate() {
        long started = System.nanoTime();
        this.authCoverage.refresh();
        this.authGridBuilt = true;
        long now = System.currentTimeMillis();
        if (now - this.lastStateCleanup >= 1000L || now < this.lastStateCleanup) {
            this.authStates.keySet().removeIf(z -> !IsoWorld.instance.currentCell.getObjectList().contains(z));
            this.lastStateCleanup = now;
        }
        ApocBRServerTelemetryLite.recordPhase("network.zombies.authGrid", System.nanoTime() - started);
    }

    public void updateAuth(IsoZombie zombie) {
        this.updateAuth(zombie, System.currentTimeMillis());
    }

    void updateAuth(IsoZombie zombie, long now) {
        if (GameServer.server) {
            if (!this.authGridBuilt) {
                this.beginAuthUpdate();
            }

            UdpConnection owner = zombie.getOwner();
            Object grapple = zombie.getWrappedGrappleable().getGrappledBy();
            AuthState state = this.authStates.get(zombie);
            boolean invalidOwner = owner != null && !this.authCoverage.eligible(owner);
            boolean debugRotation = ServerOptions.getInstance().switchZombiesOwnershipEachUpdate.getValue() && GameServer.getPlayerCount() > 1;
            boolean urgent = owner == null || invalidOwner || zombie.isDead() || state == null
                || state.owner != owner || state.target != zombie.target || state.grapple != grapple || state.onlineId != zombie.onlineId;
            if (!debugRotation && !urgent && now < state.nextCheck && now >= state.nextCheck - 500L) {
                ApocBRServerTelemetryLite.count("zombies.auth.cadenceSkipped", 1L);
                return;
            }
            // Preserve vanilla's two-second transfer cooldown for valid living owners.
            // Owner loss/disconnect and death must not wait for it.
            if (now - zombie.lastChangeOwner >= 2000L || owner == null || invalidOwner || zombie.isDead()) {
                if (state == null) { state = new AuthState(); this.authStates.put(zombie, state); }
                state.owner = owner; state.target = zombie.target; state.grapple = grapple; state.onlineId = zombie.onlineId;
                // Spread stable checks across 100 ms slots; each interval is at most 500 ms.
                state.nextCheck = now + 500L - Math.floorMod(now + Math.floorMod(zombie.getID(), 5) * 100L, 500L);
                ApocBRServerTelemetryLite.count("zombies.auth.reassessed", 1L);
                if (ServerOptions.getInstance().switchZombiesOwnershipEachUpdate.getValue() && GameServer.getPlayerCount() > 1) {
                    if (zombie.getOwner() == null) {
                        for (int i = 0; i < GameServer.udpEngine.connections.size(); i++) {
                            UdpConnection c = GameServer.udpEngine.connections.get(i);
                            if (c != null) {
                                this.moveZombie(zombie, c, null);
                                break;
                            }
                        }
                    } else {
                        int idx = GameServer.udpEngine.connections.indexOf(zombie.getOwner()) + 1;

                        for (int ix = 0; ix < GameServer.udpEngine.connections.size(); ix++) {
                            UdpConnection c = GameServer.udpEngine.connections.get((ix + idx) % GameServer.udpEngine.connections.size());
                            if (c != null) {
                                this.moveZombie(zombie, c, null);
                                break;
                            }
                        }
                    }
                } else {
                    if (zombie.getWrappedGrappleable().getGrappledBy() instanceof IsoPlayer isoPlayer) {
                        UdpConnection c = GameServer.getConnectionFromPlayer(isoPlayer);
                        if (c != null && c.isFullyConnected() && !GameServer.isDelayedDisconnect(c)) {
                            this.moveZombie(zombie, c, isoPlayer);
                            return;
                        }
                    }

                    if (zombie.target instanceof IsoPlayer isoPlayerx) {
                        UdpConnection c = GameServer.getConnectionFromPlayer(isoPlayerx);
                        if (c != null && c.isFullyConnected() && !GameServer.isDelayedDisconnect(c)) {
                            float d = isoPlayerx.getRelevantAndDistance(zombie.getX(), zombie.getY(), c.getRelevantRange() - 2);
                            if (!Float.isInfinite(d)) {
                                this.moveZombie(zombie, c, isoPlayerx);
                                return;
                            }
                        }
                    }

                    UdpConnection connection = invalidOwner ? null : owner;
                    IsoPlayer player = invalidOwner ? null : zombie.getOwnerPlayer();
                    float distance = Float.POSITIVE_INFINITY;
                    if (connection != null) {
                        distance = connection.getRelevantAndDistance(zombie.getX(), zombie.getY(), zombie.getZ());
                    }

                    List<ZombieAuthCoverage.Candidate> candidates = this.authCoverage.candidates(zombie.getX(), zombie.getY());
                    for (ZombieAuthCoverage.Candidate candidate : candidates) {
                        ApocBRServerTelemetryLite.count("zombies.auth.candidatesVisited", 1L);
                        UdpConnection c = candidate.connection;
                        IsoPlayer p = candidate.player;
                        if (c != connection && !GameServer.isDelayedDisconnect(c)) {
                            float d = p.getRelevantAndDistance(zombie.getX(), zombie.getY(), candidate.relevantRange);
                            if (!Float.isInfinite(d) && (connection == null || distance > d * 1.618034F)) {
                                connection = c;
                                distance = d;
                                player = p;
                            }
                        }
                    }

                    if (connection == null && zombie.isReanimatedPlayer()) {
                        for (int nx = 0; nx < GameServer.udpEngine.connections.size(); nx++) {
                            UdpConnection c = GameServer.udpEngine.connections.get(nx);
                            if (c != connection && !GameServer.isDelayedDisconnect(c)) {
                                for (IsoPlayer px : c.players) {
                                    if (px != null && px.isDead() && px.reanimatedCorpse == zombie) {
                                        connection = c;
                                        player = px;
                                    }
                                }
                            }
                        }
                    }

                    if (connection != null && !connection.RelevantTo(zombie.getX(), zombie.getY(), (connection.getRelevantRange() - 2) * 10)) {
                        connection = null;
                    }

                    this.moveZombie(zombie, connection, player);
                }
            } else {
                ApocBRServerTelemetryLite.count("zombies.auth.transferCooldownSkipped", 1L);
            }
        }
    }

    public void moveZombie(IsoZombie zombie, UdpConnection to, IsoPlayer player) {
        if (zombie.isDead()) {
            if (zombie.getOwner() == null && zombie.getOwnerPlayer() == null) {
                zombie.die();
            } else if (NetworkVariables.ZombieState.OnGround == zombie.realState) {
                synchronized (this.owns.lock) {
                    ZombieOwnershipIndex.Group previous = this.owns.getNetworkZombie(zombie.getOwner());
                    if (previous != null) {
                        previous.zombies.remove(zombie);
                        if (previous.zombies.isEmpty()) this.owns.release(zombie.getOwner());
                    }
                    zombie.setOwner(null);
                    zombie.setOwnerPlayer(null);
                    zombie.getNetworkCharacterAI().resetSpeedLimiter();
                }

                NetworkZombiePacker.getInstance().setExtraUpdate();
            }
        } else {
            if (player != null
                && player.getVehicle() != null
                && player.getVehicle().getSpeed2D() > 2.0F
                && player.getVehicle().getDriver() != player
                && player.getVehicle().getDriver() instanceof IsoPlayer) {
                player = (IsoPlayer)player.getVehicle().getDriver();
                to = GameServer.getConnectionFromPlayer(player);
            }

            if (zombie.getOwner() != to) {
                synchronized (this.owns.lock) {
                    if (zombie.getOwner() != null) {
                        ZombieOwnershipIndex.Group nz = this.owns.getNetworkZombie(zombie.getOwner());
                        if (nz != null && !nz.zombies.remove(zombie)) {
                            DebugLog.log("moveZombie: There are no zombies in nz.zombies.");
                        }
                        if (nz != null && nz.zombies.isEmpty()) this.owns.release(zombie.getOwner());
                    }

                    if (to != null) {
                        ZombieOwnershipIndex.Group nz2 = this.owns.getNetworkZombie(to);
                        if (nz2 != null) {
                            nz2.zombies.add(zombie);
                            zombie.setOwner(to);
                            zombie.setOwnerPlayer(player);
                            zombie.getNetworkCharacterAI().resetSpeedLimiter();
                            to.timerSendZombie.reset(0L);
                        }
                    } else {
                        zombie.setOwner(null);
                        zombie.setOwnerPlayer(null);
                        zombie.getNetworkCharacterAI().resetSpeedLimiter();
                    }
                }

                zombie.lastChangeOwner = System.currentTimeMillis();
                this.authStates.remove(zombie);
                ApocBRServerTelemetryLite.count("zombies.auth.ownerChanged", 1L);
                NetworkZombiePacker.getInstance().setExtraUpdate();
            }
        }
    }

    public int getZombieAuth(UdpConnection connection, ZombieListPacket packet) {
        int hash = PZHash.fnv_32_init();
        packet.zombiesAuth.clear();
        synchronized (this.owns.lock) {
            ZombieOwnershipIndex.Group nz = this.owns.getNetworkZombie(connection);
            if (nz == null) return hash;
            nz.zombies.removeIf(zombiex -> zombiex.onlineId == -1);

            for (IsoZombie zombie : nz.zombies) {
                if (zombie.onlineId != -1) {
                    packet.zombiesAuth.add(zombie.onlineId);
                    hash = PZHash.fnv_32_hash(hash, zombie.onlineId);
                } else {
                    DebugType.General.error("getZombieAuth: zombie.OnlineID == -1");
                }
            }

            return hash;
        }
    }

    public void clearTargetAuth(IConnection connection, IsoPlayer player) {
        if (Core.debug) {
            DebugLog.log(DebugType.Multiplayer, "Clear zombies target and auth for player id=" + player.getOnlineID());
        }

        if (GameServer.server) {
            this.beginAuthUpdate();
            for (int i = 0; i < IsoWorld.instance.currentCell.getZombieList().size(); i++) {
                IsoZombie zombie = IsoWorld.instance.currentCell.getZombieList().get(i);
                if (zombie.target == player) {
                    zombie.setTarget(null);
                }

                if (zombie.getOwner() == connection) {
                    synchronized (this.owns.lock) {
                        ZombieOwnershipIndex.Group previous = this.owns.getNetworkZombie(connection);
                        if (previous != null) previous.zombies.remove(zombie);
                    }
                    zombie.setOwner(null);
                    zombie.setOwnerPlayer(null);
                    zombie.getNetworkCharacterAI().resetSpeedLimiter();
                    getInstance().updateAuth(zombie);
                }
            }
            synchronized (this.owns.lock) { this.owns.release(connection); }
        }
    }

    public static void removeZombies(UdpConnection connection) {
        int radius = (IsoChunkMap.chunkGridWidth + 2) * 8;

        for (IsoPlayer player : connection.players) {
            if (player != null) {
                ArrayList<IsoZombie> zl = IsoWorld.instance.currentCell.getZombieList();
                ArrayList<IsoZombie> zombiesForDelete = new ArrayList<>();

                for (IsoZombie zombie : zl) {
                    if (Math.abs(zombie.getX() - player.getX()) < radius && Math.abs(zombie.getY() - player.getY()) < radius) {
                        zombiesForDelete.add(zombie);
                    }
                }

                for (IsoZombie zombiex : zombiesForDelete) {
                    NetworkZombiePacker.getInstance().deleteZombie(zombiex);
                    zombiex.removeFromWorld();
                    zombiex.removeFromSquare();
                }
            }
        }
    }

    public void recheck(IConnection connection) {
        synchronized (this.owns.lock) {
            ZombieOwnershipIndex.Group nz = this.owns.getNetworkZombie(connection);
            if (nz != null) {
                nz.zombies.removeIf(zombie -> zombie.getOwner() != connection);
            }
        }
    }

}
