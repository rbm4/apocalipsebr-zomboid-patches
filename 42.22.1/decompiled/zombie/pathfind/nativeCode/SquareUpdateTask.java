// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.pathfind.nativeCode;

import zombie.iso.BentFences;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.areas.SafeHouse;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.network.GameClient;
import zombie.popman.ObjectPool;
import zombie.util.Type;

class SquareUpdateTask implements IPathfindTask {
    private static final long BIT_SOLID = 1L;
    private static final long BIT_COLLIDE_W = 2L;
    private static final long BIT_COLLIDE_N = 4L;
    private static final long BIT_STAIR_TW = 8L;
    private static final long BIT_STAIR_MW = 16L;
    private static final long BIT_STAIR_BW = 32L;
    private static final long BIT_STAIR_TN = 64L;
    private static final long BIT_STAIR_MN = 128L;
    private static final long BIT_STAIR_BN = 256L;
    private static final long BIT_SOLID_FLOOR = 512L;
    private static final long BIT_SOLID_TRANS = 1024L;
    private static final long BIT_WINDOW_W = 2048L;
    private static final long BIT_WINDOW_N = 4096L;
    private static final long BIT_CAN_PATH_W = 8192L;
    private static final long BIT_CAN_PATH_N = 16384L;
    private static final long BIT_THUMP_W = 32768L;
    private static final long BIT_THUMP_N = 65536L;
    private static final long BIT_THUMPABLE = 131072L;
    private static final long BIT_DOOR_E = 262144L;
    private static final long BIT_DOOR_S = 524288L;
    private static final long BIT_WINDOW_W_UNBLOCKED = 1048576L;
    private static final long BIT_WINDOW_N_UNBLOCKED = 2097152L;
    private static final long BIT_DOOR_W_UNBLOCKED = 4194304L;
    private static final long BIT_DOOR_N_UNBLOCKED = 8388608L;
    private static final long BIT_HOPPABLE_N = 16777216L;
    private static final long BIT_HOPPABLE_W = 33554432L;
    private static final long BIT_TARGET_PATH_W = 67108864L;
    private static final long BIT_TARGET_PATH_N = 134217728L;
    private static final long BIT_BENDABLE_W = 268435456L;
    private static final long BIT_BENDABLE_N = 536870912L;
    private static final long BIT_TALL_HOPPABLE_N = 1073741824L;
    private static final long BIT_TALL_HOPPABLE_W = 2147483648L;
    private static final long BIT_FARMING_PLANT = 4294967296L;
    private static final long ALL_SOLID_BITS = 1025L;
    private static final long ALL_STAIR_BITS = 504L;
    private int x;
    private int y;
    private int z;
    private long bits;
    private short cost;
    private IsoDirections slopedSurfaceDirection;
    private float slopedSurfaceHeightMin;
    private float slopedSurfaceHeightMax;
    private short loadId;
    private static final ObjectPool<SquareUpdateTask> pool = new ObjectPool<>(SquareUpdateTask::new, "SquareUpdateTask.pool", 4096);

    public SquareUpdateTask init(IsoGridSquare square) {
        this.x = square.x;
        this.y = square.y;
        this.z = square.z + 32;
        this.bits = getBits(square);
        this.cost = getCost(square);
        this.slopedSurfaceDirection = square.getSlopedSurfaceDirection();
        this.slopedSurfaceHeightMin = square.getSlopedSurfaceHeightMin();
        this.slopedSurfaceHeightMax = square.getSlopedSurfaceHeightMax();
        this.loadId = square.chunk.getLoadID();
        return this;
    }

    @Override
    public void execute() {
        PathfindNative.updateSquare(
            this.loadId,
            this.x,
            this.y,
            this.z,
            this.bits,
            this.cost,
            this.slopedSurfaceDirection == null ? 8 : this.slopedSurfaceDirection.ordinal(),
            this.slopedSurfaceHeightMin,
            this.slopedSurfaceHeightMax
        );
    }

    public static long getBits(IsoGridSquare sq) {
        long bits = 0L;
        if (sq.has(IsoFlagType.solidfloor)) {
            bits |= 512L;
        }

        if (sq.isSolid()) {
            bits |= 1L;
        }

        if (sq.isSolidTrans()) {
            bits |= 1024L;
        }

        if (sq.has(IsoFlagType.collideW)) {
            bits |= 2L;
        }

        if (sq.has(IsoFlagType.collideN)) {
            bits |= 4L;
        }

        if (sq.has(IsoObjectType.stairsTW)) {
            bits |= 8L;
        }

        if (sq.has(IsoObjectType.stairsMW)) {
            bits |= 16L;
        }

        if (sq.has(IsoObjectType.stairsBW)) {
            bits |= 32L;
        }

        if (sq.has(IsoObjectType.stairsTN)) {
            bits |= 64L;
        }

        if (sq.has(IsoObjectType.stairsMN)) {
            bits |= 128L;
        }

        if (sq.has(IsoObjectType.stairsBN)) {
            bits |= 256L;
        }

        if (sq.has(IsoFlagType.HoppableN)) {
            bits |= 16777216L;
        } else if ((sq.has(IsoFlagType.TallHoppableN) || sq.has(IsoFlagType.WallN) || sq.has(IsoFlagType.WallNTrans))
            && canClimbOverWall(sq, IsoDirections.N)
            && canClimbOverWall(sq, IsoDirections.S)) {
            bits |= 1073741824L;
        }

        if (sq.has(IsoFlagType.HoppableW)) {
            bits |= 33554432L;
        } else if ((sq.has(IsoFlagType.TallHoppableW) || sq.has(IsoFlagType.WallW) || sq.has(IsoFlagType.WallWTrans))
            && canClimbOverWall(sq, IsoDirections.W)
            && canClimbOverWall(sq, IsoDirections.E)) {
            bits |= 2147483648L;
        }

        if (sq.has(IsoFlagType.windowW) || sq.has(IsoFlagType.WindowW)) {
            bits |= 2050L;
            if (isWindowUnblocked(sq, false)) {
                bits |= 1048576L;
            }
        }

        if (sq.has(IsoFlagType.windowN) || sq.has(IsoFlagType.WindowN)) {
            bits |= 4100L;
            if (isWindowUnblocked(sq, true)) {
                bits |= 2097152L;
            }
        }

        if (sq.has(IsoFlagType.canPathW)) {
            bits |= 8192L;
        }

        if (sq.has(IsoFlagType.canPathN)) {
            bits |= 16384L;
        }

        boolean bHasDoorW = false;
        boolean bHasDoorN = false;

        for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
            IsoObject obj = sq.getSpecialObjects().get(i);
            IsoDirections dir = null;
            if (obj instanceof IsoDoor isoDoor) {
                dir = isoDoor.getSpriteEdge(false);
                if (isoDoor.IsOpen()) {
                    dir = isoDoor.getSpriteEdge(true);
                    if (dir == IsoDirections.N) {
                        bits |= 8388608L;
                    } else if (dir == IsoDirections.W) {
                        bits |= 4194304L;
                    }

                    dir = null;
                }
            } else if (obj instanceof IsoThumpable isoThumpable && isoThumpable.isDoor()) {
                dir = isoThumpable.getSpriteEdge(false);
                if (isoThumpable.IsOpen()) {
                    dir = isoThumpable.getSpriteEdge(true);
                    if (dir == IsoDirections.N) {
                        bits |= 8388608L;
                    } else if (dir == IsoDirections.W) {
                        bits |= 4194304L;
                    }

                    dir = null;
                }
            }

            if (dir == IsoDirections.W) {
                bits |= 8192L;
                bits |= 2L;
                bHasDoorW = true;
            } else if (dir == IsoDirections.N) {
                bits |= 16384L;
                bits |= 4L;
                bHasDoorN = true;
            } else if (dir == IsoDirections.S) {
                bits |= 524288L;
            } else if (dir == IsoDirections.E) {
                bits |= 262144L;
            }
        }

        if (sq.has(IsoFlagType.DoorWallW)) {
            bits |= 8192L;
            bits |= 2L;
            if (!bHasDoorW) {
                bits |= 4194304L;
            }
        }

        if (sq.has(IsoFlagType.DoorWallN)) {
            bits |= 16384L;
            bits |= 4L;
            if (!bHasDoorN) {
                bits |= 8388608L;
            }
        }

        if (hasSquareThumpable(sq)) {
            bits |= 8192L;
            bits |= 16384L;
            bits |= 131072L;
            if (!sq.isSolid() && !sq.isSolidTrans()) {
                bits |= 1024L;
            }
        }

        if (sq.hasLitCampfire()) {
            bits |= 8192L;
            bits |= 16384L;
            bits |= 131072L;
            bits |= 1024L;
        }

        if (hasWallThumpableN(sq)) {
            bits |= 81920L;
        }

        if (hasWallThumpableW(sq)) {
            bits |= 40960L;
        }

        if (BentFences.getInstance().isEnabled()) {
            if (hasWallBendableN(sq)) {
                bits |= 16384L;
                bits |= 65536L;
                bits |= 536870912L;
                bits |= 134217728L;
            }

            if (hasWallBendableW(sq)) {
                bits |= 8192L;
                bits |= 32768L;
                bits |= 268435456L;
                bits |= 67108864L;
            }
        }

        if (sq.hasFarmingPlant()) {
            bits |= 4294967296L;
        }

        return bits;
    }

    public static short getCost(IsoGridSquare sq) {
        short cost = 0;
        if (sq.HasTree() || sq.hasBush()) {
            cost = (short)(cost + 5);
        }

        return cost;
    }

    static boolean isWindowUnblocked(IsoGridSquare sq, boolean north) {
        for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
            IsoObject special = sq.getSpecialObjects().get(i);
            if (special instanceof IsoThumpable thump && thump.isWindow() && north == thump.north) {
                if (thump.isBarricaded()) {
                    return false;
                }

                return true;
            }

            if (special instanceof IsoWindow window && north == window.isNorth()) {
                if (window.isBarricaded()) {
                    return false;
                }

                if (window.isInvincible()) {
                    return false;
                }

                if (window.IsOpen()) {
                    return true;
                }

                if (window.isDestroyed() && window.isGlassRemoved()) {
                    return true;
                }

                return false;
            }
        }

        IsoWindowFrame frame = sq.getWindowFrame(north ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST);
        return frame != null && frame.canClimbThrough(null);
    }

    private static boolean hasSquareThumpable(IsoGridSquare sq) {
        if (sq.HasStairs()) {
            return false;
        } else {
            for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
                IsoThumpable thump = Type.tryCastTo(sq.getSpecialObjects().get(i), IsoThumpable.class);
                if (thump != null && thump.isThumpable() && thump.isBlockAllTheSquare()) {
                    return true;
                }
            }

            for (int ix = 0; ix < sq.getObjects().size(); ix++) {
                IsoObject obj = sq.getObjects().get(ix);
                if (obj.isMovedThumpable()) {
                    return true;
                }
            }

            return false;
        }
    }

    private static boolean hasWallThumpableN(IsoGridSquare sq) {
        for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
            if (sq.getSpecialObjects().get(i) instanceof IsoThumpable thump
                && !thump.canClimbThrough(null)
                && (!thump.canClimbOver(null) || thump.isTallHoppable())
                && thump.isThumpable()
                && !thump.isBlockAllTheSquare()
                && !thump.isDoor()) {
                return (thump.isWallN() || thump.isCorner()) && !thump.isCanPassThrough();
            }
        }

        return false;
    }

    private static boolean hasWallThumpableW(IsoGridSquare sq) {
        for (int i = 0; i < sq.getSpecialObjects().size(); i++) {
            if (sq.getSpecialObjects().get(i) instanceof IsoThumpable thump
                && !thump.canClimbThrough(null)
                && (!thump.canClimbOver(null) || thump.isTallHoppable())
                && thump.isThumpable()
                && !thump.isBlockAllTheSquare()
                && !thump.isDoor()) {
                return (thump.isWallW() || thump.isCorner()) && !thump.isCanPassThrough();
            }
        }

        return false;
    }

    private static boolean hasWallBendableW(IsoGridSquare sq) {
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (BentFences.getInstance().isUnbentObject(obj) && obj.isWallW()) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasWallBendableN(IsoGridSquare sq) {
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (BentFences.getInstance().isUnbentObject(obj) && obj.isWallN()) {
                return true;
            }
        }

        return false;
    }

    private static boolean canClimbOverWall(IsoGridSquare square, IsoDirections dir) {
        if (square == null) {
            return false;
        } else {
            IsoGridSquare squareAdjacent = square.getAdjacentSquare(dir);
            if (squareAdjacent == null) {
                return false;
            } else if (square.haveRoof || squareAdjacent.haveRoof) {
                return false;
            } else if (!square.TreatAsSolidFloor() || !squareAdjacent.TreatAsSolidFloor()) {
                return false;
            } else if (IsoWindow.isSheetRopeHere(square) || IsoWindow.isSheetRopeHere(squareAdjacent)) {
                return false;
            } else if (square.getBuilding() != null || squareAdjacent.getBuilding() != null) {
                return false;
            } else if (square.has(IsoFlagType.water) || squareAdjacent.has(IsoFlagType.water)) {
                return false;
            } else if (square.has(IsoFlagType.CantClimb) || squareAdjacent.has(IsoFlagType.CantClimb)) {
                return false;
            } else if (!square.isSolid() && !square.isSolidTrans() && !squareAdjacent.isSolid() && !squareAdjacent.isSolidTrans()) {
                IsoGridSquare above = IsoWorld.instance.currentCell.getGridSquare(square.x, square.y, square.z + 1);
                if (above != null && above.HasSlopedRoof() && !above.HasEave()) {
                    return false;
                } else {
                    IsoGridSquare above2 = IsoWorld.instance.currentCell.getGridSquare(squareAdjacent.x, squareAdjacent.y, squareAdjacent.z + 1);
                    if (above2 != null && above2.HasSlopedRoof() && !above2.HasEave()) {
                        return false;
                    } else {
                        return (above == null || !above.has(IsoFlagType.collideN)) && (above2 == null || !above2.has(IsoFlagType.collideN))
                            ? !GameClient.client || SafeHouse.getSafeHouse(square) == null && SafeHouse.getSafeHouse(squareAdjacent) == null
                            : false;
                    }
                }
            } else {
                return false;
            }
        }
    }

    private static boolean canClimbDownSheetRope(IsoGridSquare sq) {
        if (sq == null) {
            return false;
        } else {
            for (int startZ = sq.getZ();
                sq != null;
                sq = IsoWorld.instance.currentCell.getGridSquare((double)sq.getX(), (double)sq.getY(), (double)(sq.getZ() - 1.0F))
            ) {
                if (!IsoWindow.isSheetRopeHere(sq)) {
                    return false;
                }

                if (!IsoWindow.canClimbHere(sq)) {
                    return false;
                }

                if (sq.TreatAsSolidFloor()) {
                    return sq.getZ() < startZ;
                }
            }

            return false;
        }
    }

    public static SquareUpdateTask alloc() {
        return pool.alloc();
    }

    @Override
    public void release() {
        pool.release(this);
    }
}
