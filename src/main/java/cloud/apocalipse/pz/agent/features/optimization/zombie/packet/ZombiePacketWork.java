package cloud.apocalipse.pz.agent.features.optimization.zombie.packet;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.characters.NetworkZombieVariables.Flag;
import zombie.network.NetworkVariables.WalkType;
import zombie.network.NetworkVariables.ZombieState;
import zombie.pathfind.Path;
import zombie.pathfind.PathFindBehavior2;
import zombie.pathfind.PathNode;

/** Local operations used by the synchronous packet preparation path. */
public final class ZombiePacketWork {
    private static final WalkType[] WALK_TYPES = WalkType.values();
    private static final Map<String, WalkType> WALK_TYPES_BY_NAME = walkTypesByName();
    private static final Map<String, ZombieState> STATES_BY_NAME = statesByName();

    private ZombiePacketWork() {
    }

    public static WalkType walkType(String value) {
        if (value != null) {
            WalkType exact = WALK_TYPES_BY_NAME.get(value);
            if (exact != null) {
                return exact;
            }
            // equalsIgnoreCase includes Unicode folds that ASCII lowercasing would miss.
            for (WalkType type : WALK_TYPES) {
                if (type.toString().equalsIgnoreCase(value)) {
                    return type;
                }
            }
        }
        return WalkType.WT1;
    }

    public static ZombieState state(String value) {
        return value == null ? ZombieState.Idle : STATES_BY_NAME.getOrDefault(value.toLowerCase(), ZombieState.Idle);
    }

    private static Map<String, WalkType> walkTypesByName() {
        Map<String, WalkType> result = new HashMap<>();
        for (WalkType type : WALK_TYPES) {
            result.putIfAbsent(type.toString(), type);
        }
        return Map.copyOf(result);
    }

    private static Map<String, ZombieState> statesByName() {
        Map<String, ZombieState> result = new HashMap<>();
        for (ZombieState state : ZombieState.values()) {
            result.putIfAbsent(state.toString(), state);
        }
        return Map.copyOf(result);
    }

    public static short flags(IsoZombie zombie) {
        int flags = 0;
        if (zombie.isFakeDead()) flags |= Flag.IsFakeDead.flag();
        if (zombie.lunger) flags |= Flag.IsLunger.flag();
        if (zombie.running) flags |= Flag.IsRunning.flag();
        if (zombie.isCrawling()) flags |= Flag.IsCrawling.flag();
        if (zombie.isSitAgainstWall()) flags |= Flag.IsSitAgainstWall.flag();
        if (zombie.isReanimatedPlayer()) flags |= Flag.IsReanimatedPlayer.flag();
        if (zombie.isOnFire()) flags |= Flag.IsOnFire.flag();
        if (zombie.isUseless()) flags |= Flag.IsUseless.flag();
        if (zombie.isOnFloor()) flags |= Flag.IsOnFloor.flag();
        if (zombie.isReanimatedForGrappleOnly()) flags |= Flag.IsReanimatedForGrappleOnly.flag();
        if (zombie.isCanWalk()) flags |= Flag.IsCanWalk.flag();
        if (zombie.isSkeleton()) flags |= Flag.IsSkeleton.flag();
        if (zombie.isFallOnFront()) flags |= Flag.IsFallOnFront.flag();
        if (zombie.isAnimationRecorderActive()) flags |= Flag.IsAnimationRecording.flag();
        return (short) flags;
    }

    public static long captureDirection(IsoZombie zombie) {
        return ((long) Float.floatToRawIntBits(zombie.getForwardDirectionX()) << 32)
            | Integer.toUnsignedLong(Float.floatToRawIntBits(zombie.getForwardDirectionY()));
    }

    public static float directionX(long snapshot) {
        return Float.intBitsToFloat((int) (snapshot >>> 32));
    }

    public static float directionY(long snapshot) {
        return Float.intBitsToFloat((int) snapshot);
    }

    /**
     * This call site only asks whether vanilla's float length exceeds five.
     * Continue checking later deltas for NaN after crossing it, because a later
     * NaN would make the vanilla comparison false.
     */
    public static float pathLengthForPrediction(PathFindBehavior2 behavior) {
        Path path = (Path) PathFields.PATH.get(behavior);
        int nextIndex = (int) PathFields.INDEX.get(behavior) + 1;
        if (path == null || path.isEmpty() || nextIndex >= path.size()) {
            return behavior.getPathLength();
        }

        IsoGameCharacter character = (IsoGameCharacter) PathFields.CHARACTER.get(behavior);
        PathNode previous = path.getNode(nextIndex);
        float length = (float) Math.sqrt(squaredDistance(character.getX(), character.getY(), previous));
        int rootsSkipped = 0;
        for (int index = nextIndex + 1; index < path.size(); index++) {
            PathNode node = path.getNode(index);
            float squared = squaredDistance(previous.x, previous.y, node);
            if (Float.isNaN(squared)) {
                length = Float.NaN;
                break;
            }
            if (length > 5.0F) {
                rootsSkipped++;
            } else {
                length += (float) Math.sqrt(squared);
            }
            previous = node;
        }
        if (rootsSkipped != 0) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.PACKET_PATH_SQRT_SKIPPED, rootsSkipped);
        }
        return length;
    }

    private static float squaredDistance(float x, float y, PathNode node) {
        float dx = x - node.x;
        float dy = y - node.y;
        return dx * dx + dy * dy;
    }

    private static final class PathFields {
        private static final VarHandle PATH = field("path", Path.class);
        private static final VarHandle INDEX = field("pathIndex", int.class);
        private static final VarHandle CHARACTER = field("chr", IsoGameCharacter.class);

        private static VarHandle field(String name, Class<?> type) {
            try {
                return MethodHandles.privateLookupIn(PathFindBehavior2.class, MethodHandles.lookup())
                    .findVarHandle(PathFindBehavior2.class, name, type);
            } catch (ReflectiveOperationException invalidGameClass) {
                throw new IllegalStateException("Cannot link the vanilla zombie prediction path", invalidGameClass);
            }
        }
    }
}
