// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.popman;

import java.util.ArrayList;
import zombie.SandboxOptions;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.statistics.data.GameStatistic;

public class ZombieCountOptimiser {
    private static int zombieCountForDelete;
    public static final ArrayList<IsoZombie> zombiesForDelete = new ArrayList<>();

    public static void startCount() {
        zombieCountForDelete = (int)(
            1.0F * Math.max(0, IsoWorld.instance.getCell().getZombieList().size() - SandboxOptions.instance.zombieConfig.zombiesCountBeforeDeletion.getValue())
        );
    }

    public static void incrementZombie(IsoZombie zombie) {
        if (zombieCountForDelete > 0
            && Rand.Next(Rand.AdjustForFramerate(10)) == 0
            && !zombie.isReanimatedPlayer()
            && zombie.getTarget() == null
            && isOutside(zombie)
            && canBeDeletedUnnoticed(zombie)) {
            zombiesForDelete.add(zombie);
            GameStatistic.getInstance().zombiesCulled.increase();
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

    private static boolean canBeDeletedUnnoticed(IsoZombie zombie) {
        if (!GameServer.server) {
            return false;
        } else {
            for (IsoPlayer player : GameServer.IDToPlayerMap.values()) {
                UdpConnection connection = GameServer.getConnectionFromPlayer(player);
                if (connection != null) {
                    float distance = IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), player.getX(), player.getY());
                    float screenDistance = (connection.getRelevantRange() - 2) * 10 / 2.0F;
                    if (distance <= screenDistance * screenDistance) {
                        return false;
                    }
                }
            }

            return true;
        }
    }

    private static boolean isOutside(IsoZombie zombie) {
        return zombie.getCurrentSquare() == null || !zombie.getCurrentSquare().isInARoom() && !zombie.getCurrentSquare().haveRoof;
    }
}
