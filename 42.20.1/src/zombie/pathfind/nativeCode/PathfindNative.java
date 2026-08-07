// Patched PathfindNative.java - Track active chunk loadIds to guard against
// stale ChunkUpdateTask execution that crashes libPZPathFind64.so.
//
// Added:
//   - static ConcurrentHashMap<Long, Short> activeChunkLoadIds: keyed by
//     chunkKey(wx, wy), stores the loadId that was active when the chunk was
//     added to native pathfind state.
//   - static long chunkKey(int wx, int wy): deterministic key for the map.
//   - addChunkToWorld(): registers (wx, wy) -> loadId BEFORE queuing the task.
//   - removeChunkFromWorld(): unregisters (wx, wy) BEFORE queuing the remove task.
//   - stop(): clears the map when the pathfind system shuts down.
//
// ChunkUpdateTask.execute() reads activeChunkLoadIds to skip calls whose chunk
// has since been removed or reloaded, preventing the SIGSEGV in Square::init().
//
// Ported forward from build 42.19.0 (was missing in 42.20.0/42.20.1 - a real
// regression: the original stale-chunk crash class is still reachable here).
//
// Additionally hardened findPath(PathFindRequest, ByteBuffer, boolean) with
// input validation: NaN/Infinite or degenerate start/target coordinates are
// rejected before crossing into native code, as defense-in-depth against a
// separate observed crash (malloc(): invalid size (unsorted) inside
// PolygonalMap::findPathHighLevelThenLowLevel / reallocate_aligned). This
// mirrors the NaN/Infinite guards already applied to the Java-fallback
// PolygonalMap2/LineClearCollideMain pathfinding code.
//
// Original: zombie.pathfind.nativeCode.PathfindNative (Build 42.20.1)
package zombie.pathfind.nativeCode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.ai.astar.Mover;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.gameStates.DebugChunkState;
import zombie.gameStates.IngameState;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.pathfind.IPathfinder;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.TestRequest;
import zombie.vehicles.BaseVehicle;

public class PathfindNative {
    private static final IsoDirections[] DIRECTIONS = IsoDirections.values();
    public static final PathfindNative instance = new PathfindNative();
    public static boolean useNativeCode = true;
    private final HashMap<BaseVehicle, VehicleState> vehicleState = new HashMap<>();
    private int testZ;
    private final ByteBuffer requestBb = ByteBuffer.allocateDirect(50);
    private final PathFindRequest request = new PathFindRequest();
    private final TestRequest finder = new TestRequest();
    private boolean testRequestAdded;

    // === ApocBR pathfind safety: active chunk registry =======================
    // Maps chunkKey(wx, wy) -> loadId for every chunk currently registered in
    // native pathfind state. Written on the main thread (add/removeChunkFromWorld),
    // read on the PathfindNativeThread. ConcurrentHashMap is used for safe
    // cross-thread access without explicit locking.
    static final ConcurrentHashMap<Long, Short> activeChunkLoadIds = new ConcurrentHashMap<>();

    /**
     * Deterministic map key for a chunk world position.
     * 1_000_003 is prime; the product won't collide for any realistic wx/wy
     * range in Project Zomboid maps (max ~12 000 chunks on each axis).
     */
    static long chunkKey(int wx, int wy) {
        return (long) wx * 1_000_003L + wy;
    }
    // =========================================================================

    public static void init() {
        String libSuffix = "";
        if ("1".equals(System.getProperty("zomboid.debuglibs.pathfind"))) {
            DebugLog.log("***** Loading debug version of PZPathFind");
            libSuffix = "d";
        }

        if (System.getProperty("os.name").contains("OS X")) {
            System.loadLibrary("PZPathFind");
        } else {
            System.loadLibrary("PZPathFind64" + libSuffix);
        }
    }

    public static native void initWorld(int var0, int var1, int var2, int var3, boolean var4);

    public static native void destroyWorld();

    public static native void freeMemoryAtExit();

    public static native void update();

    public static native void updateChunk(int var0, int var1, int var2, ByteBuffer var3);

    public static native void removeChunk(int var0, int var1);

    public static native void updateSquare(int var0, int var1, int var2, int var3, int var4, short var5, int var6, float var7, float var8);

    public static native void addVehicle(ByteBuffer var0);

    public static native void removeVehicle(int var0);

    public static native void teleportVehicle(ByteBuffer var0);

    public static native int findPath(ByteBuffer var0, ByteBuffer var1);

    public void init(IsoMetaGrid metaGrid) {
        initWorld(metaGrid.getMinX(), metaGrid.getMinY(), metaGrid.getWidth(), metaGrid.getHeight(), GameServer.server);
        PathfindNativeThread.instance = new PathfindNativeThread();
        ByteBuffer bb = PathfindNativeThread.instance.pathBb;
        PathfindNativeThread.instance.pathBb.order(ByteOrder.BIG_ENDIAN);
        bb.clear();
        PathfindNativeThread.instance.setName("PathfindNativeThread");
        PathfindNativeThread.instance.setDaemon(true);
        PathfindNativeThread.instance.start();
    }

    public void stop() {
        PathfindNativeThread.instance.stopThread();
        PathfindNativeThread.instance.cleanup();
        PathfindNativeThread.instance = null;

        // === ApocBR: clear registry on shutdown ==============================
        activeChunkLoadIds.clear();
        // =====================================================================

        for (VehicleState state : this.vehicleState.values()) {
            state.release();
        }

        this.vehicleState.clear();
        this.testRequestAdded = false;
        destroyWorld();
    }

    public void checkUseNativeCode() {
        if (useNativeCode != DebugOptions.instance.pathfindUseNativeCode.getValue()) {
            if (useNativeCode) {
                this.stop();
            } else {
                PolygonalMap2.instance.stop();
            }

            useNativeCode = DebugOptions.instance.pathfindUseNativeCode.getValue();
            if (useNativeCode) {
                this.init(IsoWorld.instance.metaGrid);
            } else {
                PolygonalMap2.instance.init(IsoWorld.instance.metaGrid);
            }

            for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
                IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(playerIndex);
                if (!chunkMap.ignore) {
                    for (int y = 0; y < IsoChunkMap.chunkGridWidth; y++) {
                        for (int x = 0; x < IsoChunkMap.chunkGridWidth; x++) {
                            IsoChunk chunk = chunkMap.getChunk(x, y);
                            if (chunk != null) {
                                if (useNativeCode) {
                                    this.addChunkToWorld(chunk);
                                } else {
                                    PolygonalMap2.instance.addChunkToWorld(chunk);
                                }
                            }
                        }
                    }
                }
            }

            for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
                if (useNativeCode) {
                    this.addVehicle(vehicle);
                } else {
                    PolygonalMap2.instance.addVehicleToWorld(vehicle);
                }
            }
        }
    }

    public void addChunkToWorld(IsoChunk chunk) {
        // === ApocBR: register chunk loadId before queuing ====================
        // Registering BEFORE queuing ensures that if the pathfind thread
        // immediately polls the task, the map entry is already visible.
        activeChunkLoadIds.put(chunkKey(chunk.wx, chunk.wy), chunk.getLoadID());
        // =====================================================================
        ChunkUpdateTask task = ChunkUpdateTask.alloc().init(chunk);
        PathfindNativeThread.instance.chunkTaskQueue.add(task);
        PathfindNativeThread.instance.wake();
        chunk.loadedBits = (short)(chunk.loadedBits | 2);
    }

    public void removeChunkFromWorld(IsoChunk chunk) {
        if (PathfindNativeThread.instance != null) {
            // === ApocBR: unregister chunk before queuing remove task ==========
            // Removing BEFORE queuing ensures that any ChunkUpdateTask for this
            // chunk that is still in the queue will see a missing/mismatched
            // entry and skip the native call.
            activeChunkLoadIds.remove(chunkKey(chunk.wx, chunk.wy));
            // =================================================================
            ChunkRemoveTask task = ChunkRemoveTask.alloc().init(chunk);
            PathfindNativeThread.instance.chunkTaskQueue.add(task);
            PathfindNativeThread.instance.wake();
        }
    }

    public void squareChanged(IsoGridSquare square) {
        if ((square.chunk.loadedBits & 2) != 0) {
            for (int i = 0; i < DIRECTIONS.length; i++) {
                IsoDirections dir = DIRECTIONS[i];
                IsoGridSquare square2 = square.getAdjacentSquare(dir);
                if (square2 != null) {
                    SquareUpdateTask task = SquareUpdateTask.alloc().init(square2);
                    PathfindNativeThread.instance.squareTaskQueue.add(task);
                }
            }

            SquareUpdateTask task = SquareUpdateTask.alloc().init(square);
            PathfindNativeThread.instance.squareTaskQueue.add(task);
            PathfindNativeThread.instance.wake();
        }
    }

    public void addVehicle(BaseVehicle vehicle) {
        VehicleState state = this.vehicleState.get(vehicle);
        if (state == null) {
            state = VehicleState.alloc();
            this.vehicleState.put(vehicle, state);
        } else {
            boolean task = true;
        }

        state.init(vehicle);
        VehicleAddTask task = VehicleAddTask.alloc().init(vehicle);
        PathfindNativeThread.instance.vehicleTaskQueue.add(task);
        PathfindNativeThread.instance.wake();
    }

    public void removeVehicle(BaseVehicle vehicle) {
        VehicleState vehicleState1 = this.vehicleState.remove(vehicle);
        if (vehicleState1 != null) {
            vehicleState1.release();
        }

        if (PathfindNativeThread.instance != null) {
            VehicleRemoveTask task = VehicleRemoveTask.alloc().init(vehicle);
            PathfindNativeThread.instance.vehicleTaskQueue.add(task);
            PathfindNativeThread.instance.wake();
        }
    }

    public void updateVehicle(BaseVehicle vehicle) {
        VehicleUpdateTask task = VehicleUpdateTask.alloc().init(vehicle);
        PathfindNativeThread.instance.vehicleTaskQueue.add(task);
        PathfindNativeThread.instance.wake();
    }

    public PathFindRequest addRequest(
        IPathfinder pathfinder, Mover mover, float startX, float startY, float startZ, float targetX, float targetY, float targetZ
    ) {
        this.cancelRequest(mover);
        PathFindRequest request = PathFindRequest.alloc().init(pathfinder, mover, startX, startY, startZ, targetX, targetY, targetZ);
        PathfindNativeThread.instance.requestMap.put(mover, request);
        PathRequestTask task = PathRequestTask.alloc().init(request);
        PathfindNativeThread.instance.requestTaskQueue.add(task);
        PathfindNativeThread.instance.wake();
        return request;
    }

    public void cancelRequest(Mover mover) {
        if (PathfindNativeThread.instance != null) {
            PathFindRequest request = PathfindNativeThread.instance.requestMap.remove(mover);
            if (request != null) {
                request.cancel = true;
            }
        }
    }

    public void updateMain() {
        ConcurrentLinkedQueue<IPathfindTask> queue = PathfindNativeThread.instance.taskReturnQueue;

        for (IPathfindTask task = queue.poll(); task != null; task = queue.poll()) {
            task.release();
        }

        for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
            VehicleState state = this.vehicleState.get(vehicle);
            if (state != null && state.check()) {
                this.updateVehicle(vehicle);
            }
        }

        ConcurrentLinkedQueue<PathFindRequest> requestToMain = PathfindNativeThread.instance.requestToMain;

        for (PathFindRequest request1 = requestToMain.poll(); request1 != null; request1 = requestToMain.poll()) {
            if (PathfindNativeThread.instance.requestMap.get(request1.mover) == request1) {
                PathfindNativeThread.instance.requestMap.remove(request1.mover);
            }

            if (!request1.cancel) {
                if (request1.path.isEmpty()) {
                    request1.finder.Failed(request1.mover);
                } else {
                    request1.finder.Succeeded(request1.path, request1.mover);
                }
            }

            if (!request1.doNotRelease) {
                request1.release();
            }
        }
    }

    // === ApocBR pathfind safety: reject pathological requests ================
    // Defense-in-depth against native heap corruption observed as
    // "malloc(): invalid size (unsorted)" inside
    // PolygonalMap::findPathHighLevelThenLowLevel / reallocate_aligned.
    // A NaN/Infinite or absurdly distant start/target coordinate can drive the
    // native A* implementation into runaway buffer growth or undefined size
    // calculations. Reject these before they ever reach native code, mirroring
    // the NaN/Infinite guards already present in the Java-fallback
    // PolygonalMap2 / LineClearCollideMain implementation.
    private static final float MAX_PATHFIND_COORD = 100000.0F;

    private static boolean isValidCoord(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && Math.abs(v) <= MAX_PATHFIND_COORD;
    }

    private boolean isValidRequest(PathFindRequest request) {
        return isValidCoord(request.startX)
            && isValidCoord(request.startY)
            && isValidCoord(request.startZ)
            && isValidCoord(request.targetX)
            && isValidCoord(request.targetY)
            && isValidCoord(request.targetZ);
    }
    // ==========================================================================

    public int findPath(PathFindRequest request, ByteBuffer pathBB, boolean bRender) {
        // === ApocBR: reject pathological requests before native call =========
        if (!this.isValidRequest(request)) {
            DebugType.General
                .warn(
                    "PathfindNative.findPath: rejecting invalid request (start="
                        + request.startX
                        + ","
                        + request.startY
                        + ","
                        + request.startZ
                        + " target="
                        + request.targetX
                        + ","
                        + request.targetY
                        + ","
                        + request.targetZ
                        + ")"
                );
            pathBB.clear();
            return 0;
        }
        // =======================================================================
        this.requestBb.clear();
        this.requestBb.putFloat(request.startX);
        this.requestBb.putFloat(request.startY);
        this.requestBb.putFloat(request.startZ + 32.0F);
        this.requestBb.putFloat(request.targetX);
        this.requestBb.putFloat(request.targetY);
        this.requestBb.putFloat(request.targetZ + 32.0F);
        boolean bNPC = false;
        int moverType = 0;
        if (request.mover instanceof IsoPlayer isoPlayer && !(request.mover instanceof IsoAnimal)) {
            moverType = 1;
            bNPC = isoPlayer.isNpc();
        }

        if (request.mover instanceof IsoZombie) {
            moverType = 2;
        }

        this.requestBb.putInt(moverType);
        this.requestBb.put((byte)(bNPC ? 1 : 0));
        this.requestBb.put((byte)(request.canCrawl ? 1 : 0));
        this.requestBb.put((byte)(request.crawling ? 1 : 0));
        this.requestBb.put((byte)(request.ignoreCrawlCost ? 1 : 0));
        this.requestBb.put((byte)(request.canThump ? 1 : 0));
        this.requestBb.put((byte)(request.canClimbFences ? 1 : 0));
        this.requestBb.put((byte)(request.hasTarget ? 1 : 0));
        this.requestBb.put((byte)(request.canBend ? 1 : 0));
        this.requestBb.putInt(request.minLevel);
        this.requestBb.putInt(request.maxLevel);
        this.requestBb.put((byte)(bRender ? 1 : 0));
        this.requestBb.put((byte)(request.canClimbTallFences ? 1 : 0));
        pathBB.clear();
        return findPath(this.requestBb, pathBB);
    }

    public void render() {
        if (Core.debug) {
            if (IsoCamera.frameState.playerIndex == 0) {
                if (DebugOptions.instance.pathfindPathToMouseEnable.getValue()) {
                    IsoPlayer player = IsoPlayer.players[0];
                    if (player == null || player.isDead()) {
                        return;
                    }

                    if (GameKeyboard.isKeyPressed(209)) {
                        this.testZ = Math.max(this.testZ - 1, -32);
                    }

                    if (GameKeyboard.isKeyPressed(201)) {
                        this.testZ = Math.min(this.testZ + 1, 31);
                    }

                    float x = Mouse.getX();
                    float y = Mouse.getY();
                    int z = this.testZ;
                    float targetX = IsoUtils.XToIso(x, y, z);
                    float targetY = IsoUtils.YToIso(x, y, z);
                    float targetZ = z;
                    this.renderGridAtMouse(targetX, targetY, targetZ);
                    this.pathToMouse(player.getX(), player.getY(), player.getZ(), targetX, targetY, targetZ);
                }
            }
        }
    }

    private void renderGridAtMouse(float targetX, float targetY, float targetZ) {
        int targetXi = PZMath.fastfloor(targetX);
        int targetYi = PZMath.fastfloor(targetY);
        int targetZi = PZMath.fastfloor(targetZ);

        for (int dy = -1; dy <= 2; dy++) {
            LineDrawer.addLine(targetXi - 1, targetYi + dy, targetZi, targetXi + 2, targetYi + dy, targetZi, 0.3F, 0.3F, 0.3F, null, false);
        }

        for (int dx = -1; dx <= 2; dx++) {
            LineDrawer.addLine(targetXi + dx, targetYi - 1, targetZi, targetXi + dx, targetYi + 2, targetZi, 0.3F, 0.3F, 0.3F, null, false);
        }

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(targetXi + dx, targetYi + dy, targetZi);
                if (sq == null || sq.isSolid() || sq.isSolidTrans() || sq.HasStairs()) {
                    LineDrawer.addLine(targetXi + dx, targetYi + dy, targetZi, targetXi + dx + 1, targetYi + dy + 1, targetZi, 0.3F, 0.0F, 0.0F, null, false);
                }
            }
        }

        if (this.testZ < PZMath.fastfloor(IsoPlayer.getInstance().getZ())) {
            LineDrawer.addLine(
                targetXi + 0.5F, targetYi + 0.5F, targetZi,
                targetXi + 0.5F, targetYi + 0.5F, PZMath.fastfloor(IsoPlayer.getInstance().getZ()),
                0.5F, 0.5F, 0.5F, null, true
            );
        } else if (this.testZ > PZMath.fastfloor(IsoPlayer.getInstance().getZ())) {
            LineDrawer.addLine(
                targetXi + 0.5F, targetYi + 0.5F, targetZi,
                targetXi + 0.5F, targetYi + 0.5F, PZMath.fastfloor(IsoPlayer.getInstance().getZ()),
                0.5F, 0.5F, 0.5F, null, true
            );
        }
    }

    private void pathToMouse(float startX, float startY, float startZ, float targetX, float targetY, float targetZ) {
        if (this.testRequestAdded) {
            if (this.finder.done) {
                this.testRequestAdded = false;
                if (GameWindow.states.current == IngameState.instance && !GameTime.isGamePaused() && Mouse.isButtonDown(0) && GameKeyboard.isKeyDown(42)) {
                    IsoPlayer.players[0].StopAllActionQueue();
                    Object obj = LuaManager.env.rawget("ISPathFindAction_pathToLocationF");
                    if (obj != null) {
                        LuaManager.caller.pcall(LuaManager.thread, obj, this.request.targetX, this.request.targetY, this.request.targetZ);
                    }
                }
            }
        } else {
            this.finder.path.clear();
            this.finder.done = false;
            this.request.init(this.finder, IsoPlayer.getInstance(), startX, startY, startZ, targetX, targetY, targetZ);
            this.request.doNotRelease = true;
            if (DebugOptions.instance.pathfindPathToMouseAllowCrawl.getValue()) {
                this.request.canCrawl = true;
                if (DebugOptions.instance.pathfindPathToMouseIgnoreCrawlCost.getValue()) {
                    this.request.ignoreCrawlCost = true;
                }
            }

            if (DebugOptions.instance.pathfindPathToMouseAllowThump.getValue()) {
                this.request.canThump = true;
            }

            PathRequestTask task = PathRequestTask.alloc();
            task.init(this.request);
            PathfindNativeThread.instance.requestTaskQueue.add(task);
            this.testRequestAdded = true;
            PathfindNativeThread.instance.wake();
        }

        if (GameWindow.states.current == DebugChunkState.instance) {
            this.updateMain();
        }
    }
}
