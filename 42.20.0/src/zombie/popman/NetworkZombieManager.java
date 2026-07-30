// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.ArrayList;
import zombie.ai.State;
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
    private final NetworkZombieList owns = new NetworkZombieList();
    private static final float NospottedDistanceSquared = 16.0F;

    public static NetworkZombieManager getInstance() {
        return instance;
    }

    public int getAuthorizedZombieCount(UdpConnection con) {
        return 0;
    }

    public int getUnauthorizedZombieCount() {
        return IsoWorld.instance.currentCell.getZombieList().size();
    }

    public static boolean canSpotted(IsoZombie zombie) {
        if (!GameServer.server && zombie.isRemoteZombie()) {
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

    public void updateAuth(IsoZombie zombie) {
        if (GameServer.server && (zombie.getOwner() != null || zombie.getOwnerPlayer() != null)) {
            this.moveZombie(zombie, null, null);
        }
    }

    public void moveZombie(IsoZombie zombie, UdpConnection to, IsoPlayer player) {
        to = null;
        player = null;
        if (zombie.isDead()) {
            if (zombie.getOwner() == null && zombie.getOwnerPlayer() == null) {
                zombie.die();
            } else if (NetworkVariables.ZombieState.OnGround == zombie.realState) {
                synchronized (this.owns.lock) {
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
                        NetworkZombieList.NetworkZombie nz = this.owns.getNetworkZombie(zombie.getOwner());
                        if (nz != null && !nz.zombies.remove(zombie)) {
                            DebugLog.log("moveZombie: There are no zombies in nz.zombies.");
                        }
                    }

                    if (to != null) {
                        NetworkZombieList.NetworkZombie nz2 = this.owns.getNetworkZombie(to);
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
                NetworkZombiePacker.getInstance().setExtraUpdate();
            }
        }
    }

    public int getZombieAuth(UdpConnection connection, ZombieListPacket packet) {
        int hash = PZHash.fnv_32_init();
        NetworkZombieList.NetworkZombie nz = this.owns.getNetworkZombie(connection);
        packet.zombiesAuth.clear();
        synchronized (this.owns.lock) {
            nz.zombies.clear();
            return hash;
        }
    }

    public void clearTargetAuth(IConnection connection, IsoPlayer player) {
        if (Core.debug) {
            DebugLog.log(DebugType.Multiplayer, "Clear zombies target and auth for player id=" + player.getOnlineID());
        }

        if (GameServer.server) {
            for (int i = 0; i < IsoWorld.instance.currentCell.getZombieList().size(); i++) {
                IsoZombie zombie = IsoWorld.instance.currentCell.getZombieList().get(i);
                if (zombie.target == player) {
                    zombie.setTarget(null);
                }

                zombie.setOwner(null);
                zombie.setOwnerPlayer(null);
                zombie.getNetworkCharacterAI().resetSpeedLimiter();
            }
        }
    }

    public static void removeZombies(UdpConnection connection) {
    }

    public void recheck(IConnection connection) {
        synchronized (this.owns.lock) {
            NetworkZombieList.NetworkZombie nz = this.owns.getNetworkZombie(connection);
            if (nz != null) {
                nz.zombies.clear();
            }
        }
    }
}
