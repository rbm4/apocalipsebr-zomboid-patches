// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.ArrayList;
import java.util.List;
import zombie.SandboxOptions;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.statistics.data.GameStatistic;

public class ZombieCountOptimiser {
    private static final List<IsoZombie> zombiesForDelete = new ArrayList<>();

    public static void prepareZombiesForDeletion() {
        if (GameServer.udpEngine != null) {
            int zombiesCountBeforeDeletion = SandboxOptions.instance.zombieConfig.zombiesCountBeforeDeletion.getValue();
            if (zombiesCountBeforeDeletion > 0) {
                for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                    UdpConnection connection = GameServer.udpEngine.connections.get(n);
                    List<Short> zombiesOnlineIdList = NetworkZombiePacker.getInstance().zombiesToSend.get(connection);
                    if (zombiesOnlineIdList != null && !zombiesOnlineIdList.isEmpty()) {
                        int zombiesCountForDelete = Math.max(0, zombiesOnlineIdList.size() - zombiesCountBeforeDeletion);
                        if (zombiesCountForDelete == 0) continue;

                        for (Short zombieOnlineId : zombiesOnlineIdList) {
                            if (zombiesCountForDelete == 0) break;
                            IsoZombie zombie = ServerMap.instance.zombieMap.get(zombieOnlineId);
                            if (zombie != null
                                && zombiesCountForDelete > 0
                                && Rand.Next(Rand.AdjustForFramerate(10)) == 0
                                && !zombie.isReanimatedPlayer()
                                && zombie.getTarget() == null
                                && isOutside(zombie)
                                && canBeDeletedUnnoticed(zombie, connection)) {
                                zombiesForDelete.add(zombie);
                                GameStatistic.getInstance().zombiesCulled.increase();
                                zombiesCountForDelete--;
                            }
                        }
                    }
                }
            }
        }
    }

    public static void deleteZombies() {
        if (!zombiesForDelete.isEmpty()) {
            for (IsoZombie zombieForDelete : zombiesForDelete) {
                NetworkZombiePacker.getInstance().deleteZombie(zombieForDelete);
                zombieForDelete.removeFromWorld();
                zombieForDelete.removeFromSquare();
            }

            zombiesForDelete.clear();
        }
    }

    private static boolean canBeDeletedUnnoticed(IsoZombie zombie, UdpConnection connection) {
        return GameServer.server && !IsoPlayer.anyPlayerMP(zombie, connection, ZombieCountOptimiser::isZombieInsidePlayerRelevantRange);
    }

    private static boolean isZombieInsidePlayerRelevantRange(IsoPlayer player, UdpConnection playerConnection, IsoZombie zombie, UdpConnection zombieConnection) {
        float distance = IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), player.getX(), player.getY());
        float relevantDistance = (zombieConnection.getRelevantRange() - 2) * 10;
        float relevantDistanceSq = relevantDistance * relevantDistance;
        return distance <= relevantDistanceSq;
    }

    private static boolean isOutside(IsoZombie zombie) {
        return zombie.getCurrentSquare() == null || !zombie.getCurrentSquare().isInARoom() && !zombie.getCurrentSquare().haveRoof;
    }
}
