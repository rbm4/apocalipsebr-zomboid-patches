package cloud.apocalipse.pz.agent.features.optimization.los;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Stack;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.iso.IsoCell;
import zombie.iso.IsoUtils;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ServerLOS;
import zombie.network.packets.INetworkPacket;
import zombie.popman.NetworkZombieManager;

/** Owner-thread view used only by dedicated-server LOS completion. */
public final class ServerZombiePerception {
    private static final int SIDE = 16;
    private static final HashMap<Long, List<Candidate>> CELLS = new HashMap<>();
    private static TrackedZombieList indexed;
    private static long version;
    private static boolean finitePositions;

    private ServerZombiePerception() {
    }

    public static boolean complete(IsoPlayer player, Stack<?> spotted, Stack<?> lastSpotted) {
        if (!ServerLosModule.usesAgentLos()) {
            return false;
        }

        spotted.clear();
        lastSpotted.clear();
        var stats = player.getStats();
        stats.numVisibleZombies = 0;
        stats.musicZombiesTargetingDistantNotMoving = 0;
        stats.musicZombiesTargetingNearbyNotMoving = 0;
        stats.musicZombiesTargetingDistantMoving = 0;
        stats.musicZombiesTargetingNearbyMoving = 0;
        stats.musicZombiesVisible = 0;
        stats.lastNumVisibleZombies = 0;
        stats.lastVeryCloseZombies = 0;
        player.setNumSurvivorsInVicinity(0);
        player.closestZombie = 1_000_000.0F;
        IsoCell cell = player.getCell();
        if (cell == null || player.getCurrentSquare() == null) {
            return true;
        }

        float x = player.getX();
        float y = player.getY();
        int z = PZMath.fastfloor(player.getZ());
        float radius = Math.max(20.0F, GameTime.getInstance().getViewDist());
        float radiusSquared = radius * radius;
        List<IsoZombie> candidates = candidates(cell.getZombieList(), x, y, radius);
        OptimizationTelemetry.count(OptimizationTelemetry.Counter.LOS_ZOMBIE_CANDIDATES, candidates.size());
        for (IsoZombie zombie : candidates) {
            if (zombie == null || zombie.isDead() || zombie.isFakeDead() || zombie.isUseless()
                    || zombie.isReanimatedForGrappleOnly()) {
                continue;
            }
            var square = zombie.getCurrentSquare();
            if (square == null || PZMath.fastfloor(zombie.getZ()) != z) {
                continue;
            }
            float distance = IsoUtils.DistanceToSquared(zombie.getX(), zombie.getY(), x, y);
            if (distance > radiusSquared) {
                continue;
            }
            boolean targeting = zombie.getTarget() == player;
            boolean leadAggro = !targeting && zombie.isLeadAggro(player);
            if ((!leadAggro && !targeting && !NetworkZombieManager.canSpotted(zombie))
                    || !ServerLOS.instance.isCouldSee(player, square)) {
                continue;
            }
            if (leadAggro) {
                INetworkPacket.send(zombie.getOwner(), PacketType.ZombieControl, zombie, player);
            } else {
                zombie.spotted(player, false);
                if (distance < player.closestZombie * player.closestZombie && !zombie.isOnFloor()) {
                    player.closestZombie = (float) Math.sqrt(distance);
                }
            }
        }
        return true;
    }

    private static List<IsoZombie> candidates(ArrayList<IsoZombie> zombies, float x, float y, float radius) {
        if (!(zombies instanceof TrackedZombieList tracked) || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(radius) || radius > 512 || Math.abs(x) > 8_000_000 || Math.abs(y) > 8_000_000) {
            return new ArrayList<>(zombies);
        }
        if (tracked != indexed || tracked.version() != version) {
            rebuild(tracked);
        }
        if (!finitePositions) {
            return new ArrayList<>(zombies);
        }

        List<Candidate> nearby = new ArrayList<>();
        // Include one extra tile at the edges to retain float-rounding boundary candidates.
        for (int column = tile(x - radius) - 1; column <= tile(x + radius) + 1; column++) {
            for (int row = tile(y - radius) - 1; row <= tile(y + radius) + 1; row++) {
                var members = CELLS.get(key(column, row));
                if (members != null) {
                    nearby.addAll(members);
                }
            }
        }
        nearby.sort(Comparator.comparingInt(Candidate::position));
        List<IsoZombie> result = new ArrayList<>(nearby.size());
        for (Candidate candidate : nearby) {
            result.add(candidate.zombie);
        }
        return result;
    }

    private static void rebuild(TrackedZombieList tracked) {
        CELLS.clear();
        finitePositions = true;
        for (int position = 0; position < tracked.size(); position++) {
            IsoZombie zombie = tracked.get(position);
            if (zombie != null) {
                float x = zombie.getX();
                float y = zombie.getY();
                if (!Float.isFinite(x) || !Float.isFinite(y)) {
                    finitePositions = false;
                    CELLS.clear();
                    break;
                }
                CELLS.computeIfAbsent(key(tile(x), tile(y)), ignored -> new ArrayList<>())
                    .add(new Candidate(position, zombie));
            }
        }
        indexed = tracked;
        version = tracked.version();
    }

    public static void moved(Object object, float before, float after) {
        if (indexed != null && object instanceof IsoZombie
                && (tile(before) != tile(after) || Float.isFinite(before) != Float.isFinite(after))) {
            invalidate();
        }
    }

    static void invalidate() {
        indexed = null;
        CELLS.clear();
    }

    public static ArrayList<IsoZombie> track(ArrayList<IsoZombie> original) {
        return new TrackedZombieList(original);
    }

    private static int tile(float coordinate) {
        return PZMath.fastfloor(coordinate / SIDE);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ Integer.toUnsignedLong(y);
    }

    private record Candidate(int position, IsoZombie zombie) {
    }
}
