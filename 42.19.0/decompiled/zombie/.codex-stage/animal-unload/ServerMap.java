// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import zombie.core.PZForkJoinPool;
import zombie.GameTime;
import zombie.MapCollisionData;
import zombie.ReanimatedPlayers;
import zombie.VirtualZombieManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.Roles;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.core.ImportantAreaManager;
import zombie.core.backup.ZipBackup;
import zombie.core.logger.LoggerManager;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.core.stash.StashSystem;
import zombie.core.utils.OnceEvery;
import zombie.core.utils.UpdateLimit;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.GameEntityManager;
import zombie.ApocBRServerTelemetry;
import zombie.globalObjects.SGlobalObjects;
import zombie.iso.InstanceTracker;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.MetaTracker;
import zombie.iso.RoomDef;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.WorldGenerate;
import zombie.iso.worldgen.WorldGenParams;
import zombie.network.ServerMap.ServerCell;
import zombie.network.id.ObjectIDManager;
import zombie.network.packets.INetworkPacket;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombiePopulationManager;
import zombie.radio.ZomboidRadio;
import zombie.savefile.ServerPlayerDB;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclesDB2;
import zombie.world.moddata.GlobalModData;
import zombie.worldMap.network.WorldMapServer;
import zombie.core.PZForkJoinPool;
import java.util.concurrent.CompletableFuture;

public class ServerMap {
    public boolean updateLosThisFrame;
    public static final OnceEvery LOS_TICK = new OnceEvery(1.0F);
    public static final OnceEvery TIME_TICK = new OnceEvery(600.0F);
    public static final int CellSize = 64;
    public static final int ChunksPerCellWidth = 8;
    public long lastSaved;
    private static boolean mapLoading;
    private static final long SAVE_CLIENT_PAUSE_THRESHOLD_MS = 600L;
    private long saveStartTime;
    private boolean saveClientPaused;
    private boolean saveQuitFlag;
    private final UpdateLimit metaEntitySaveFrequency = new UpdateLimit(1000L);
    public final IsoObjectID<IsoZombie> zombieMap = new IsoObjectID<>(IsoZombie.class);
    private static final int SAVE_CELL_COUNT_MULTITHREAD_THRESHOLD = 10;
    private static final int SAVE_CELL_WORK_THREADS = 4;
    private static final ServerMap.WorkerThread[] workerThreads = new ServerMap.WorkerThread[4];
    public boolean queuedSaveAll;
    public boolean queuedQuit;
    private volatile boolean asyncSaveRunning = false; // PATCH-E: guards against overlapping background saves
    public static ServerMap instance = new ServerMap();
    public ServerMap.ServerCell[] cellMap;
    public ArrayList<ServerMap.ServerCell> loadedCells = new ArrayList<>();
    public Set<ServerMap.ServerCell> releventNow = ConcurrentHashMap.newKeySet();
    int width;
    int height;
    IsoMetaGrid grid;
    ArrayList<ServerMap.ServerCell> toLoad = new ArrayList<>();
    static final ServerMap.DistToCellComparator distToCellComparator = new ServerMap.DistToCellComparator();
    private final ArrayList<ServerMap.ServerCell> tempCells = new ArrayList<>();
    // Main-thread-only queue. LinkedHashMap keeps the first irrelevant cell first.
    private static final long DEFERRED_UNLOAD_GRACE_MS = 5000L;
    private static final int MAX_DEFERRED_UNLOADS_PER_TICK = 1;
    private final LinkedHashMap<ServerMap.ServerCell, Long> pendingUnloads = new LinkedHashMap<>();
    // Main-thread finalization of worker-recalculated cells. The worker has already
    // prepared the cell; this queue limits only the live-world commit in Load2().
    private static final long FINALIZE_BUDGET_NORMAL_NANOS = 20_000_000L;
    private static final long FINALIZE_BUDGET_ELEVATED_NANOS = 12_000_000L;
    private static final long FINALIZE_BUDGET_HIGH_NANOS = 8_000_000L;
    private static final long FINALIZE_BUDGET_CRITICAL_NANOS = 4_000_000L;
    private static final long FINALIZE_FRAME_ELEVATED_NANOS = 110_000_000L;
    private static final long FINALIZE_FRAME_HIGH_NANOS = 140_000_000L;
    private static final long FINALIZE_FRAME_CRITICAL_NANOS = 250_000_000L;
    private static final long FINALIZE_MAX_WAIT_MS = 1500L;
    private final IdentityHashMap<ServerMap.ServerCell, Long> finalizeReadySince = new IdentityHashMap<>();
    // Recalc workers stop before doLoadGridsquare(): it can execute Lua item
    // distribution callbacks and therefore must run on the main thread.
    private final ConcurrentLinkedQueue<ServerMap.ServerCell> readyForMainThreadGridLoad = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CompletedChunkUnload> completedChunkUnloads = new ConcurrentLinkedQueue<>();
    long lastTick;

    public short getUniqueZombieId() {
        return this.zombieMap.allocateID();
    }

    public void SaveAll() {
        long start = System.nanoTime();
        if (!GameServer.softReset && this.loadedCells.size() >= 10) {
            for (int i = 0; i < 4; i++) {
                workerThreads[i] = new ServerMap.WorkerThread();
                workerThreads[i].setDaemon(true);
                workerThreads[i].start();
            }

            for (int n = 0; n < this.loadedCells.size(); n++) {
                ServerMap.ServerCell cell = this.loadedCells.get(n);
                workerThreads[n % 4].putCommand(ServerMap.EThreadCommand.SaveCell, cell);
                cell.UpdateVehicle();
            }

            for (int i = 0; i < 4; i++) {
                workerThreads[i].putCommand(ServerMap.EThreadCommand.Quit, null);
            }

            while (true) {
                boolean running = false;

                for (int i = 0; i < 4; i++) {
                    if (!workerThreads[i].quit) {
                        running = true;
                        break;
                    }
                }

                if (!running) {
                    Arrays.fill(workerThreads, null);
                    ServerMap.ServerCell.chunkLoader.updateSaved();
                    break;
                }

                ServerMap.ServerCell.chunkLoader.updateSaved();
                this.checkClientPause();

                try {
                    Thread.sleep(10L);
                } catch (InterruptedException var5) {
                }
            }
        } else {
            for (ServerMap.ServerCell cell : this.loadedCells) {
                cell.Save(false);
                cell.UpdateVehicle();
                this.checkClientPause();
            }
        }

        this.grid.save();
        DebugLog.log("SaveAll took " + (System.nanoTime() - start) / 1000000.0 + " ms");
    }

    public void QueueSaveAll() {
        this.queuedSaveAll = true;
    }

    public void QueueQuit() {
        this.queuedQuit = true;
    }

    public int toServerCellX(int x) {
        return PZMath.coorddivision(x * 256, 64);
    }

    public int toServerCellY(int y) {
        return PZMath.coorddivision(y * 256, 64);
    }

    public int toWorldCellX(int x) {
        return PZMath.coorddivision(x * 64, 256);
    }

    public int toWorldCellY(int y) {
        return PZMath.coorddivision(y * 64, 256);
    }

    public int getMaxX() {
        int x = this.toServerCellX(this.grid.maxX + 1);
        if ((this.grid.maxX + 1) * 256 % 64 == 0) {
            x--;
        }

        return x;
    }

    public int getMaxY() {
        int y = this.toServerCellY(this.grid.maxY + 1);
        if ((this.grid.maxY + 1) * 256 % 64 == 0) {
            y--;
        }

        return y;
    }

    public int getMinX() {
        return this.toServerCellX(this.grid.minX);
    }

    public int getMinY() {
        return this.toServerCellY(this.grid.minY);
    }

    public void init(IsoMetaGrid metaGrid) {
        this.grid = metaGrid;
        this.width = this.getMaxX() - this.getMinX() + 1;
        this.height = this.getMaxY() - this.getMinY() + 1;

        assert this.width * 64 >= metaGrid.getWidth() * 256;

        assert this.height * 64 >= metaGrid.getHeight() * 256;

        assert this.getMaxX() * 64 < (metaGrid.getMaxX() + 1) * 256;

        assert this.getMaxY() * 64 < (metaGrid.getMaxY() + 1) * 256;

        int tot = this.width * this.height;
        this.cellMap = new ServerMap.ServerCell[tot];
        StashSystem.init();
    }

    public ServerMap.ServerCell getCell(int x, int y) {
        return this.isInvalidCell(x, y) ? null : this.cellMap[y * this.width + x];
    }

    public boolean isInvalidCell(int x, int y) {
        return x < 0 || y < 0 || x >= this.width || y >= this.height;
    }

    public void loadOrKeepRelevent(int x, int y) {
        if (!this.isInvalidCell(x, y)) {
            ServerMap.ServerCell cell = this.getCell(x, y);
            if (cell == null) {
                cell = new ServerMap.ServerCell();
                cell.wx = x + this.getMinX();
                cell.wy = y + this.getMinY();
                if (cell.wx == -1 && cell.wy == -1) {
                    return;
                }

                if (mapLoading) {
                    DebugType.MapLoading
                            .debugln("Loading cell: " + cell.wx + ", " + cell.wy + " (" + this.toWorldCellX(cell.wx)
                                    + ", " + this.toWorldCellY(cell.wy) + ")");
                }

                this.cellMap[y * this.width + x] = cell;
                this.toLoad.add(cell);
                this.loadedCells.add(cell);
                this.releventNow.add(cell);
            } else if (!this.releventNow.contains(cell)) {
                this.releventNow.add(cell);
            }
        }
    }

    public void characterIn(IsoPlayer p) {
        while (this.grid == null) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException var9) {
                DebugType.General.printException(var9, LogSeverity.Error);
            }
        }

        int dist = p.onlineChunkGridWidth / 2 * 8;
        int minX = PZMath.fastfloor((p.getX() - dist) / 64.0F) - this.getMinX();
        int maxX = PZMath.fastfloor((p.getX() + dist) / 64.0F) - this.getMinX();
        int minY = PZMath.fastfloor((p.getY() - dist) / 64.0F) - this.getMinY();
        int maxY = PZMath.fastfloor((p.getY() + dist) / 64.0F) - this.getMinY();

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                this.loadOrKeepRelevent(xx, yy);
            }
        }
    }

    public void characterIn(int wx, int wy, int chunkGridWidth) {
        while (this.grid == null) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException var17) {
                DebugType.General.printException(var17, LogSeverity.Error);
            }
        }

        int x = wx * 8;
        int y = wy * 8;
        x = PZMath.coorddivision(x, 64);
        y = PZMath.coorddivision(y, 64);
        x -= this.getMinX();
        y -= this.getMinY();
        int cx = PZMath.fastfloor((float) x);
        int cy = PZMath.fastfloor((float) y);
        int lx = wx * 8 % 64;
        int ly = wy * 8 % 64;
        int dist = chunkGridWidth / 2 * 8;
        int minX = cx;
        int minY = cy;
        int maxX = cx;
        int maxY = cy;
        if (lx < dist) {
            minX = cx - 1;
        }

        if (lx > 64 - dist) {
            maxX = cx + 1;
        }

        if (ly < dist) {
            minY = cy - 1;
        }

        if (ly > 64 - dist) {
            maxY = cy + 1;
        }

        for (int yy = minY; yy <= maxY; yy++) {
            for (int xx = minX; xx <= maxX; xx++) {
                this.loadOrKeepRelevent(xx, yy);
            }
        }
    }

    public void importantAreaIn(int sx, int sy) {
        while (this.grid == null) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException var4) {
                DebugType.General.printException(var4, LogSeverity.Error);
            }
        }

        this.loadOrKeepRelevent(sx - this.getMinX(), sy - this.getMinY());
    }

    public void QueuedQuit() {
        ZipBackup.waitFinish();
        this.QueuedSaveAll(true);
        ByteBufferWriter b = GameServer.udpEngine.startPacket();
        PacketTypes.PacketType.ServerQuit.doPacket(b);
        GameServer.udpEngine.endPacketBroadcast(PacketTypes.PacketType.ServerQuit);
        WorldGenerate.instance.stop();

        try {
            Thread.sleep(5000L);
        } catch (InterruptedException var3) {
            DebugType.General.printException(var3, LogSeverity.Error);
        }

        Roles.save();
        PathfindNative.instance.stop();
        PathfindNative.freeMemoryAtExit();
        MapCollisionData.instance.stop();
        AnimalPopulationManager.getInstance().stop();
        ZombiePopulationManager.instance.stop();
        RCONServer.shutdown();
        ServerMap.ServerCell.chunkLoader.quit();
        ServerWorldDatabase.instance.close();
        ServerPlayersVehicles.instance.stop();
        ServerPlayerDB.getInstance().close();
        ObjectIDManager.getInstance().checkForSaveDataFile(true);
        ImportantAreaManager.getInstance().saveDataFile();
        VehiclesDB2.instance.Reset();
        GameServer.udpEngine.Shutdown();
        ServerGUI.shutdown();
        SteamUtils.shutdown();
    }

    private void checkClientPause() {
        if (!this.saveQuitFlag && !this.saveClientPaused && System.nanoTime() - this.saveStartTime >= 600000000L) {
            INetworkPacket.sendToAll(PacketTypes.PacketType.StartPause);
            this.saveClientPaused = true;
            System.out.println("Pausing clients because saving is taking longer than 600ms");
        }
    }

    public void QueuedSaveAll(boolean quit) {
        // PATCH-E: wait for any in-progress background save before running the blocking
        // quit-save
        if (quit) {
            while (this.asyncSaveRunning) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ignored) {
                }
            }
        }
        this.saveQuitFlag = quit;
        this.saveClientPaused = false;
        this.saveStartTime = System.nanoTime();
        this.SaveAll();
        this.checkClientPause();
        ServerPlayerDB.getInstance().save();
        this.checkClientPause();
        ServerMap.ServerCell.chunkLoader.saveLater(GameTime.instance);
        this.checkClientPause();
        ReanimatedPlayers.instance.saveReanimatedPlayers();
        this.checkClientPause();
        AnimalPopulationManager.getInstance().save();
        this.checkClientPause();
        MapCollisionData.instance.save();
        this.checkClientPause();
        SGlobalObjects.save();
        this.checkClientPause();
        WorldGenParams.INSTANCE.save();
        this.checkClientPause();
        InstanceTracker.save();
        this.checkClientPause();
        MetaTracker.save();
        this.checkClientPause();

        try {
            ZomboidRadio.getInstance().Save();
        } catch (Exception var4) {
            DebugType.General.printException(var4, LogSeverity.Error);
        }

        this.checkClientPause();

        try {
            GlobalModData.instance.save();
        } catch (Exception var3) {
            DebugType.General.printException(var3, LogSeverity.Error);
        }

        this.checkClientPause();
        GameEntityManager.Save();
        this.checkClientPause();
        WorldMapServer.instance.writeSavefile();
        if (quit) {
            ServerPlayerDB.getInstance().saveFinishWait();
        } else if (this.saveClientPaused) {
            INetworkPacket.sendToAll(PacketTypes.PacketType.StopPause);
        }

        System.out.println("Saving finish");
        DebugLog.log("Saving took " + (System.nanoTime() - this.saveStartTime) / 1000000.0 + " ms");
    }

    // PATCH-E: background save implementation - called from a daemon thread
    // ServerPlayerDB.save() is intentionally excluded here: it was called on the
    // main thread
    // before this thread started (see E-2) to avoid iterating the live connection
    // list off-thread.
    private void runAsyncSave(ArrayList<ServerMap.ServerCell> cells) {
        long saveStart = System.nanoTime();
        System.out.println("[PATCH-E] Background save started (" + cells.size() + " cells)");
        try {
            if (!GameServer.softReset && cells.size() >= 10) {
                for (int i = 0; i < 4; i++) {
                    workerThreads[i] = new WorkerThread();
                    workerThreads[i].setDaemon(true);
                    workerThreads[i].start();
                }
                for (int n = 0; n < cells.size(); n++) {
                    workerThreads[n % 4].putCommand(ServerMap.EThreadCommand.SaveCell, cells.get(n));
                    cells.get(n).UpdateVehicle();
                }
                for (int i = 0; i < 4; i++) {
                    workerThreads[i].putCommand(ServerMap.EThreadCommand.Quit, null);
                }
                while (true) {
                    boolean running = false;
                    for (int i = 0; i < 4; i++) {
                        if (!workerThreads[i].quit) {
                            running = true;
                            break;
                        }
                    }
                    if (!running) {
                        Arrays.fill(workerThreads, null);
                        ServerMap.ServerCell.chunkLoader.updateSaved();
                        break;
                    }
                    ServerMap.ServerCell.chunkLoader.updateSaved();
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignored) {
                    }
                }
            } else {
                for (ServerMap.ServerCell cell : cells) {
                    cell.Save(false);
                    cell.UpdateVehicle();
                }
            }
            this.grid.save();
            ServerMap.ServerCell.chunkLoader.saveLater(GameTime.instance);
            ReanimatedPlayers.instance.saveReanimatedPlayers();
            AnimalPopulationManager.getInstance().save();
            MapCollisionData.instance.save();
            SGlobalObjects.save();
            WorldGenParams.INSTANCE.save();
            InstanceTracker.save();
            MetaTracker.save();
            try {
                ZomboidRadio.getInstance().Save();
            } catch (Exception e) {
                DebugType.General.printException(e, LogSeverity.Error);
            }
            try {
                GlobalModData.instance.save();
            } catch (Exception e) {
                DebugType.General.printException(e, LogSeverity.Error);
            }
            GameEntityManager.Save();
            WorldMapServer.instance.writeSavefile();
        } catch (Exception e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
        System.out.println(
                "[PATCH-E] Background save finished in " + (System.nanoTime() - saveStart) / 1000000.0 + " ms");
    }

    public void preupdate() {
        long preupdateStartNanos = System.nanoTime();
        long previousFrameNanos = this.lastTick == 0L ? 0L : preupdateStartNanos - this.lastTick;
        this.lastTick = preupdateStartNanos;
        mapLoading = DebugType.MapLoading.isEnabled();
        this.publishReadyCells(previousFrameNanos);
        this.drainCompletedChunkUnloads();

        for (int i = 0; i < this.toLoad.size(); i++) {
            ServerMap.ServerCell cell = this.toLoad.get(i);
            if (cell.loadingWasCancelled) {
                if (mapLoading) {
                    DebugType.MapLoading.debugln("MainThread: forgetting cancelled " + cell.wx + "," + cell.wy);
                }

                int cx = cell.wx - this.getMinX();
                int cy = cell.wy - this.getMinY();

                assert this.cellMap[cx + cy * this.width] == cell;

                this.cellMap[cx + cy * this.width] = null;
                this.loadedCells.remove(cell);
                this.releventNow.remove(cell);
                ServerMap.ServerCell.loaded2.remove(cell);
                this.finalizeReadySince.remove(cell);
                this.toLoad.remove(i--);
            }
        }

        for (int ix = 0; ix < this.loadedCells.size(); ix++) {
            ServerMap.ServerCell cell = this.loadedCells.get(ix);
            if (cell.cancelLoading) {
                if (mapLoading) {
                    DebugType.MapLoading.debugln("MainThread: forgetting cancelled " + cell.wx + "," + cell.wy);
                }

                int cx = cell.wx - this.getMinX();
                int cy = cell.wy - this.getMinY();

                assert this.cellMap[cx + cy * this.width] == cell;

                this.cellMap[cx + cy * this.width] = null;
                this.loadedCells.remove(ix--);
                this.releventNow.remove(cell);
                ServerMap.ServerCell.loaded2.remove(cell);
                this.finalizeReadySince.remove(cell);
                this.toLoad.remove(cell);
            }
        }

        for (int ixx = 0; ixx < ServerMap.ServerCell.loaded2.size(); ixx++) {
            ServerMap.ServerCell cell = ServerMap.ServerCell.loaded2.get(ixx);
            if (cell.cancelLoading) {
                if (mapLoading) {
                    DebugType.MapLoading.debugln("MainThread: forgetting cancelled " + cell.wx + "," + cell.wy);
                }

                int cx = cell.wx - this.getMinX();
                int cy = cell.wy - this.getMinY();

                assert this.cellMap[cx + cy * this.width] == cell;

                this.cellMap[cx + cy * this.width] = null;
                this.loadedCells.remove(cell);
                this.releventNow.remove(cell);
                ServerMap.ServerCell.loaded2.remove(cell);
                this.finalizeReadySince.remove(cell);
                this.toLoad.remove(cell);
            }
        }

        if (!this.toLoad.isEmpty()) {
            this.tempCells.clear();

            for (int ixxx = 0; ixxx < this.toLoad.size(); ixxx++) {
                ServerMap.ServerCell cell = this.toLoad.get(ixxx);
                if (!cell.cancelLoading && !cell.startedLoading) {
                    this.tempCells.add(cell);
                }
            }

            if (!this.tempCells.isEmpty()) {
                distToCellComparator.init();
                this.tempCells.sort(distToCellComparator);

                for (int ixxxx = 0; ixxxx < this.tempCells.size(); ixxxx++) {
                    ServerMap.ServerCell cell = this.tempCells.get(ixxxx);
                    ServerMap.ServerCell.chunkLoader.addJob(cell);
                    cell.startedLoading = true;
                }
            }

            ServerMap.ServerCell.chunkLoader.getLoaded(ServerMap.ServerCell.loaded);

            for (int ixxxx = 0; ixxxx < ServerMap.ServerCell.loaded.size(); ixxxx++) {
                ServerMap.ServerCell cell = ServerMap.ServerCell.loaded.get(ixxxx);
                if (!cell.doingRecalc) {
                    ServerMap.ServerCell.chunkLoader.addRecalcJob(cell);
                    cell.doingRecalc = true;
                }
            }

            ServerMap.ServerCell.loaded.clear();
            this.finalizeReadyCells(previousFrameNanos);
        }

        int saveWorldEveryMinutes = ServerOptions.instance.saveWorldEveryMinutes.getValue();
        if (saveWorldEveryMinutes > 0) {
            long currentTime = System.currentTimeMillis();
            if (currentTime > this.lastSaved + saveWorldEveryMinutes * 60L * 1000L) {
                this.queuedSaveAll = true;
                this.lastSaved = currentTime;
            }
        }

        if (this.queuedSaveAll && !ZipBackup.isRunning()) {
            this.queuedSaveAll = false;
            if (!this.asyncSaveRunning) { // PATCH-E: non-blocking periodic save
                this.asyncSaveRunning = true;
                // ServerPlayerDB.save() iterates the live connection list - must run on the
                // main thread
                ServerPlayerDB.getInstance().save();
                final ArrayList<ServerMap.ServerCell> cellsSnapshot = new ArrayList<>(this.loadedCells);
                final Thread saveThread = new Thread(() -> {
                    try {
                        this.runAsyncSave(cellsSnapshot);
                    } finally {
                        this.asyncSaveRunning = false;
                    }
                }, "ServerMap-AsyncSave");
                saveThread.setDaemon(true);
                saveThread.start();
            } else {
                System.out.println("[PATCH-E] Skipping periodic save: previous async save still running");
            }
        }

        if (this.queuedQuit) {
            System.exit(0);
        }

        this.releventNow.clear();
        this.updateLosThisFrame = LOS_TICK.Check();
        if (TIME_TICK.Check()) {
            ServerMap.ServerCell.chunkLoader.saveLater(GameTime.instance);
        }

        if (GameEntityManager.needSave && this.metaEntitySaveFrequency.Check()) {
            GameEntityManager.Save();
        }
    }

    /**
     * Commits worker-recalculated cells to the live world on the main thread. This
     * is
     * intentionally time-sliced: loading/recalc remains asynchronous, while the
     * non-thread-safe world mutation in RecalcAll2() is spread across server
     * frames.
     */
    private void finalizeReadyCells(long previousFrameNanos) {
        long nowMs = System.currentTimeMillis();
        int readyBeforeDrain = ServerMap.ServerCell.loaded2.size();
        ServerMap.ServerCell.chunkLoader.getRecalc(ServerMap.ServerCell.loaded2);
        int received = 0;
        for (int i = readyBeforeDrain; i < ServerMap.ServerCell.loaded2.size(); i++) {
            ServerCell cell = ServerMap.ServerCell.loaded2.get(i);
            if (!this.finalizeReadySince.containsKey(cell)) {
                this.finalizeReadySince.put(cell, nowMs);
                received++;
            }
        }

        for (int i = ServerMap.ServerCell.loaded2.size() - 1; i >= 0; i--) {
            ServerCell cell = ServerMap.ServerCell.loaded2.get(i);
            if (cell.cancelLoading) {
                ServerMap.ServerCell.loaded2.remove(i);
                this.finalizeReadySince.remove(cell);
            }
        }

        int finalized = 0;
        long finalizeNanos = 0L;
        long finalizeMaxNanos = 0L;
        long budgetNanos = this.getFinalizeBudgetNanos(previousFrameNanos);
        if (!ServerMap.ServerCell.loaded2.isEmpty()) {
            distToCellComparator.init();
            ServerMap.ServerCell.loaded2.sort(distToCellComparator);

            int oldestIndex = -1;
            long oldestQueuedAt = Long.MAX_VALUE;
            for (int i = 0; i < ServerMap.ServerCell.loaded2.size(); i++) {
                Long queuedAt = this.finalizeReadySince.get(ServerMap.ServerCell.loaded2.get(i));
                if (queuedAt != null && nowMs - queuedAt >= FINALIZE_MAX_WAIT_MS && queuedAt < oldestQueuedAt) {
                    oldestQueuedAt = queuedAt;
                    oldestIndex = i;
                }
            }
            if (oldestIndex > 0) {
                ServerCell oldest = ServerMap.ServerCell.loaded2.remove(oldestIndex);
                ServerMap.ServerCell.loaded2.add(0, oldest);
            }

            boolean losSuspended = false;
            long startNanos = System.nanoTime();
            try {
                ServerLOS.instance.suspend();
                losSuspended = true;
                while (!ServerMap.ServerCell.loaded2.isEmpty()) {
                    if (finalized > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                        break;
                    }
                    ServerCell cell = ServerMap.ServerCell.loaded2.remove(0);
                    long cellStartNanos = System.nanoTime();
                    cell.finalizeLoad();
                    long cellNanos = System.nanoTime() - cellStartNanos;
                    finalizeNanos += cellNanos;
                    finalizeMaxNanos = Math.max(finalizeMaxNanos, cellNanos);
                    this.finalizeReadySince.remove(cell);
                    this.toLoad.remove(cell);
                    finalized++;
                }
            } finally {
                if (losSuspended) {
                    ServerLOS.instance.resume();
                }
            }
        }

        long oldestAgeMs = 0L;
        for (ServerCell cell : ServerMap.ServerCell.loaded2) {
            Long queuedAt = this.finalizeReadySince.get(cell);
            if (queuedAt != null) {
                oldestAgeMs = Math.max(oldestAgeMs, nowMs - queuedAt);
            }
        }
        ApocBRServerTelemetry.recordServerMapLoadFinalize(
                ServerMap.ServerCell.loaded2.size(), received, finalized, finalizeNanos, finalizeMaxNanos,
                oldestAgeMs, budgetNanos, previousFrameNanos);
    }

    /**
     * Runs the Lua-capable grid-load phase only on the main thread. Cells remain
     * invisible until every chunk has completed this phase, then publication is
     * a small frame-boundary transition.
     */
    private void publishReadyCells(long previousFrameNanos) {
        long startNanos = System.nanoTime();
        long budgetNanos = this.getFinalizeBudgetNanos(previousFrameNanos);
        int processed = 0;
        for (ServerCell cell = this.readyForMainThreadGridLoad.poll(); cell != null;
                cell = this.readyForMainThreadGridLoad.poll()) {
            if (processed > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                this.readyForMainThreadGridLoad.add(cell);
                break;
            }

            if (cell.cancelLoading) {
                continue;
            }

            if (!cell.doNextMainThreadGridLoad()) {
                this.readyForMainThreadGridLoad.add(cell);
                processed++;
                continue;
            }

            cell.finishMainThreadGridLoad();
            if (cell.publishLoad()) {
                cell.loadVehicles();
            }
            processed++;
        }
    }

    private void drainCompletedChunkUnloads() {
        HashSet<ServerCell> finishedCells = new HashSet<>();

        for (CompletedChunkUnload completed = this.completedChunkUnloads.poll(); completed != null;
                completed = this.completedChunkUnloads.poll()) {
            try {
                completed.cell.finishChunkUnload(completed.x, completed.y, completed.chunk, completed.error);
                if (completed.cell.isUnloadFinished()) {
                    finishedCells.add(completed.cell);
                }
            } catch (Throwable t) {
                DebugType.General.printException(t, LogSeverity.Error);
            }
        }

        for (ServerCell cell : finishedCells) {
            this.detachCompletedUnloadCell(cell);
        }
    }

    private void detachCompletedUnloadCell(ServerCell cell) {
        int x = cell.wx - this.getMinX();
        int y = cell.wy - this.getMinY();
        if (!this.isInvalidCell(x, y) && this.cellMap[y * this.width + x] == cell) {
            this.cellMap[y * this.width + x] = null;
        }

        this.loadedCells.remove(cell);
        this.releventNow.remove(cell);
        this.pendingUnloads.remove(cell);
        this.toLoad.remove(cell);
        this.finalizeReadySince.remove(cell);
        ServerCell.loaded2.remove(cell);
    }

    private long getFinalizeBudgetNanos(long previousFrameNanos) {
        if (previousFrameNanos >= FINALIZE_FRAME_CRITICAL_NANOS)
            return FINALIZE_BUDGET_CRITICAL_NANOS;
        if (previousFrameNanos >= FINALIZE_FRAME_HIGH_NANOS)
            return FINALIZE_BUDGET_HIGH_NANOS;
        if (previousFrameNanos >= FINALIZE_FRAME_ELEVATED_NANOS)
            return FINALIZE_BUDGET_ELEVATED_NANOS;
        return FINALIZE_BUDGET_NORMAL_NANOS;
    }

    private static final int PARALLEL_CELL_THRESHOLD = 4;

    /**
     * Thread-safe overload of outsidePlayerInfluence: uses a pre-snapped
     * connections
     * list so worker threads do not read GameServer.udpEngine.connections
     * concurrently.
     */
    private boolean outsidePlayerInfluence(ServerCell cell, List<UdpConnection> connSnapshot) {
        int x1 = cell.wx * 64;
        int y1 = cell.wy * 64;
        int x2 = (cell.wx + 1) * 64;
        int y2 = (cell.wy + 1) * 64;
        for (int n = 0; n < connSnapshot.size(); n++) {
            UdpConnection c = connSnapshot.get(n);
            if (c.isRelevantTo(x1, y1))
                return false;
            if (c.isRelevantTo(x2, y1))
                return false;
            if (c.isRelevantTo(x2, y2))
                return false;
            if (c.isRelevantTo(x1, y2))
                return false;
        }
        return true;
    }

    private static final class CompletedChunkUnload {
        final ServerCell cell;
        final int x;
        final int y;
        final IsoChunk chunk;
        final Throwable error;

        CompletedChunkUnload(ServerCell cell, int x, int y, IsoChunk chunk, Throwable error) {
            this.cell = cell;
            this.x = x;
            this.y = y;
            this.chunk = chunk;
            this.error = error;
        }
    }

    /**
     * Result holder for the parallel partition phase.
     */
    private static final class PhaseAResult {
        final List<ServerCell> toUpdate = new ArrayList<>();
        final List<ServerCell> toUnload = new ArrayList<>();
        final List<ServerCell> toCancel = new ArrayList<>();
    }

    /**
     * Defers full ServerCell teardown so a burst of irrelevant cells cannot stall
     * one server frame.
     * This method is intentionally main-thread-only: ServerCell.Unload() mutates
     * live world state.
     */
    private void processDeferredUnloads(List<ServerCell> toUpdate, List<ServerCell> toUnload) {
        long now = System.currentTimeMillis();
        int queued = 0;
        int revalidated = 0;
        int unloaded = 0;
        int unloadAttempts = 0;
        long unloadNanos = 0L;

        for (ServerCell cell : toUpdate) {
            if (this.pendingUnloads.remove(cell) != null) {
                revalidated++;
            }
        }

        for (ServerCell cell : toUnload) {
            if (!this.pendingUnloads.containsKey(cell)) {
                this.pendingUnloads.put(cell, now);
                queued++;
            }
        }

        while (unloadAttempts < MAX_DEFERRED_UNLOADS_PER_TICK && !this.pendingUnloads.isEmpty()) {
            ServerCell cell = this.pendingUnloads.keySet().iterator().next();
            Long queuedAt = this.pendingUnloads.get(cell);
            if (queuedAt == null || now - queuedAt < DEFERRED_UNLOAD_GRACE_MS) {
                break;
            }

            // A cell can disappear through a separate load-cancellation path before it
            // reaches us.
            if (!cell.isPublished() || !this.loadedCells.contains(cell)) {
                this.pendingUnloads.remove(cell);
                continue;
            }

            boolean losSuspended = false;
            long unloadStart = System.nanoTime();
            unloadAttempts++;
            try {
                ServerLOS.instance.suspend();
                losSuspended = true;
                int x = cell.wx - this.getMinX();
                int y = cell.wy - this.getMinY();
                ServerCell mapCell = this.cellMap[y * this.width + x];
                if (mapCell != null) {
                    mapCell.Unload();
                }
                this.pendingUnloads.remove(cell);
                unloaded++;
            } catch (Exception e) {
                DebugType.General.printException(e, LogSeverity.Error);
            } finally {
                unloadNanos += System.nanoTime() - unloadStart;
                if (losSuspended) {
                    ServerLOS.instance.resume();
                }
            }
        }

        long oldestAgeMs = 0L;
        if (!this.pendingUnloads.isEmpty()) {
            Long oldestQueuedAt = this.pendingUnloads.values().iterator().next();
            if (oldestQueuedAt != null) {
                oldestAgeMs = Math.max(0L, now - oldestQueuedAt);
            }
        }
        ApocBRServerTelemetry.recordServerMapDeferredUnload(
                this.pendingUnloads.size(), queued, revalidated, unloaded, unloadNanos, oldestAgeMs);
    }

    public void postupdate() {
        final int cellCount = this.loadedCells.size();

        // Phase AP (Parallel Partition): read-only classification on ForkJoinPool,
        // then serial mutation (unloads and cancellations).
        long apocBrPhaseStart = System.nanoTime();
        ArrayList<ServerCell> cellsToUpdate = new ArrayList<>();

        if (cellCount >= PARALLEL_CELL_THRESHOLD) {
            // Snapshot for read-only access from worker threads
            final Set<ServerCell> releventSnapshot = this.releventNow;
            final ArrayList<UdpConnection> connSnapshot;
            synchronized (GameServer.udpEngine.connections) {
                connSnapshot = new ArrayList<>(GameServer.udpEngine.connections);
            }

            int numWorkers = Math.min(
                    Math.max(1, cellCount / 20 + 1),
                    Runtime.getRuntime().availableProcessors());
            int chunkSize = (cellCount + numWorkers - 1) / numWorkers;

            @SuppressWarnings("unchecked")
            CompletableFuture<PhaseAResult>[] classifyFutures = new CompletableFuture[numWorkers];

            for (int t = 0; t < numWorkers; t++) {
                final int start = t * chunkSize;
                final int end = Math.min(start + chunkSize, cellCount);
                if (start >= end) {
                    classifyFutures[t] = CompletableFuture.completedFuture(null);
                    continue;
                }
                classifyFutures[t] = CompletableFuture.supplyAsync(() -> {
                    PhaseAResult r = new PhaseAResult();
                    for (int i = start; i < end; i++) {
                        ServerCell cell = this.loadedCells.get(i);
                        boolean shouldBeLoaded = releventSnapshot.contains(cell)
                                || !this.outsidePlayerInfluence(cell, connSnapshot);
                        if (cell.isUnloading()) {
                            continue;
                        }

                        if (!cell.isPublished()) {
                            if (!shouldBeLoaded && !cell.cancelLoading) {
                                r.toCancel.add(cell);
                            }
                        } else if (!shouldBeLoaded) {
                            r.toUnload.add(cell);
                        } else {
                            r.toUpdate.add(cell);
                        }
                    }
                    return r;
                }, PZForkJoinPool.commonPool());
            }

            // Collect results
            PhaseAResult merged = new PhaseAResult();
            for (int t = 0; t < numWorkers; t++) {
                PhaseAResult r = classifyFutures[t].join();
                if (r == null)
                    continue;
                merged.toUpdate.addAll(r.toUpdate);
                merged.toUnload.addAll(r.toUnload);
                merged.toCancel.addAll(r.toCancel);
            }

            ApocBRServerTelemetry.recordWorldSection("serverMapPartition", System.nanoTime() - apocBrPhaseStart);

            // Serial mutation: cancellations (flag-setting, no pathfind pause needed)
            for (ServerCell cell : merged.toCancel) {
                if (mapLoading) {
                    DebugLog.log(
                            DebugType.MapLoading, "MainThread: cancelling " + cell.wx + "," + cell.wy
                                    + " cell.startedLoading=" + cell.startedLoading);
                }
                if (!cell.startedLoading)
                    cell.loadingWasCancelled = true;
                cell.cancelLoading = true;
            }

            this.processDeferredUnloads(merged.toUpdate, merged.toUnload);
            cellsToUpdate = new ArrayList<>(merged.toUpdate);
        } else {
            // Serial path for small cell counts (no parallelism overhead)
            ArrayList<ServerCell> toUnload = new ArrayList<>();
            for (int n = 0; n < this.loadedCells.size(); n++) {
                ServerCell cell = this.loadedCells.get(n);
                boolean shouldBeLoaded = this.releventNow.contains(cell) || !this.outsidePlayerInfluence(cell);
                if (cell.isUnloading()) {
                    continue;
                }

                if (!cell.isPublished()) {
                    if (!shouldBeLoaded && !cell.cancelLoading) {
                        if (mapLoading) {
                            DebugLog.log(
                                    DebugType.MapLoading, "MainThread: cancelling " + cell.wx + "," + cell.wy
                                            + " cell.startedLoading=" + cell.startedLoading);
                        }
                        if (!cell.startedLoading)
                            cell.loadingWasCancelled = true;
                        cell.cancelLoading = true;
                    }
                } else if (!shouldBeLoaded) {
                    toUnload.add(cell);
                } else {
                    cellsToUpdate.add(cell);
                }
            }
            this.processDeferredUnloads(cellsToUpdate, toUnload);
            ApocBRServerTelemetry.recordWorldSection("serverMapPartition", System.nanoTime() - apocBrPhaseStart);
        }

        // Phase B+C (parallel, on ForkJoinPool):
        int updatedCount = cellsToUpdate.size();
        if (updatedCount >= PARALLEL_CELL_THRESHOLD) {
            final ArrayList<ServerCell> keepCells = new ArrayList<>(cellsToUpdate);
            int numWorkers = Math.min(
                    Math.max(1, keepCells.size() / 20 + 1),
                    Runtime.getRuntime().availableProcessors());
            int chunkSize = (keepCells.size() + numWorkers - 1) / numWorkers;

            @SuppressWarnings("unchecked")
            CompletableFuture<Void>[] allFutures = new CompletableFuture[numWorkers + 1];

            long apocBrCellStart = System.nanoTime();
            for (int t = 0; t < numWorkers; t++) {
                final int start = t * chunkSize;
                final int end = Math.min(start + chunkSize, keepCells.size());
                if (start >= end) {
                    allFutures[t] = CompletableFuture.completedFuture(null);
                    continue;
                }
                allFutures[t] = CompletableFuture.runAsync(() -> {
                    for (int i = start; i < end; i++)
                        keepCells.get(i).update();
                }, PZForkJoinPool.commonPool());
            }

            allFutures[numWorkers] = CompletableFuture.runAsync(() -> {
                try {
                    long apocBrZombieNetworkStart = System.nanoTime();
                    NetworkZombiePacker.getInstance().postupdate();
                    ApocBRServerTelemetry.recordZombieNetworkPost(
                            IsoWorld.instance.currentCell.getZombieList().size(),
                            System.nanoTime() - apocBrZombieNetworkStart);
                } catch (Throwable t) {
                    DebugType.General.printException(t, LogSeverity.Error);
                }
                try {
                    ServerCell.chunkLoader.updateSaved();
                } catch (Throwable t) {
                    DebugType.General.printException(t, LogSeverity.Error);
                }
            }, PZForkJoinPool.commonPool());

            // Wait time includes both cell updates and misc tasks running in parallel
            CompletableFuture.allOf(allFutures).join();
            ApocBRServerTelemetry.recordWorldSection("serverMapCellTasks", System.nanoTime() - apocBrCellStart);
            ApocBRServerTelemetry.recordServerMapCellsUpdated(keepCells.size(), numWorkers);
        } else {
            long apocBrSequentialStart = System.nanoTime();
            for (int i = 0; i < updatedCount; i++)
                cellsToUpdate.get(i).update();
            long cellMs = System.nanoTime() - apocBrSequentialStart;
            ApocBRServerTelemetry.recordWorldSection("serverMapCellTasks", cellMs);
            ApocBRServerTelemetry.recordServerMapCellsUpdated(updatedCount, 0);

            apocBrSequentialStart = System.nanoTime();
            long apocBrZombieNetworkStart = System.nanoTime();
            NetworkZombiePacker.getInstance().postupdate();
            ApocBRServerTelemetry.recordZombieNetworkPost(
                    IsoWorld.instance.currentCell.getZombieList().size(), System.nanoTime() - apocBrZombieNetworkStart);
            ServerCell.chunkLoader.updateSaved();
            ApocBRServerTelemetry.recordWorldSection("serverMapMiscTasks", System.nanoTime() - apocBrSequentialStart);
        }
    }

    public void physicsCheck(int x, int y) {
        int cx = PZMath.coorddivision(x, 64) - this.getMinX();
        int cy = PZMath.coorddivision(y, 64) - this.getMinY();
        ServerMap.ServerCell cell = this.getCell(cx, cy);
        if (cell != null && cell.isPublished()) {
            cell.physicsCheck = true;
        }
    }

    private boolean outsidePlayerInfluence(ServerMap.ServerCell cell) {
        int x1 = cell.wx * 64;
        int y1 = cell.wy * 64;
        int x2 = (cell.wx + 1) * 64;
        int y2 = (cell.wy + 1) * 64;

        for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
            UdpConnection c = GameServer.udpEngine.connections.get(n);
            if (c.isRelevantTo(x1, y1)) {
                return false;
            }

            if (c.isRelevantTo(x2, y1)) {
                return false;
            }

            if (c.isRelevantTo(x2, y2)) {
                return false;
            }

            if (c.isRelevantTo(x1, y2)) {
                return false;
            }
        }

        return true;
    }

    public int worldSquareToServerCellXY(int worldSquareXY) {
        return PZMath.coorddivision(worldSquareXY, 64);
    }

    public int worldChunkToServerCellXY(int worldChunkXY) {
        return PZMath.coorddivision(worldChunkXY, 8);
    }

    public static IsoGridSquare getGridSquare(Vector3 v) {
        return instance.getGridSquare(PZMath.fastfloor(v.x), PZMath.fastfloor(v.y), PZMath.fastfloor(v.z));
    }

    public IsoGridSquare getGridSquare(int x, int y, int z) {
        if (!IsoWorld.instance.isValidSquare(x, y, z)) {
            return null;
        } else {
            int cx = this.worldSquareToServerCellXY(x);
            int cy = this.worldSquareToServerCellXY(y);
            int chx = (x - cx * 64) / 8;
            int chy = (y - cy * 64) / 8;
            int sqx = (x - cx * 64) % 8;
            int sqy = (y - cy * 64) % 8;
            cx -= this.getMinX();
            cy -= this.getMinY();
            ServerMap.ServerCell cell = this.getCell(cx, cy);
            if (cell != null && (cell.isPublished() || cell.isFinalizingOnCurrentThread())) {
                IsoChunk c = cell.chunks[chx][chy];
                return c == null ? null : c.getGridSquare(sqx, sqy, z);
            } else {
                return null;
            }
        }
    }

    public void setGridSquare(int x, int y, int z, IsoGridSquare sq) {
        int cx = this.worldSquareToServerCellXY(x);
        int cy = this.worldSquareToServerCellXY(y);
        int chx = (x - cx * 64) / 8;
        int chy = (y - cy * 64) / 8;
        int sqx = (x - cx * 64) % 8;
        int sqy = (y - cy * 64) % 8;
        cx -= this.getMinX();
        cy -= this.getMinY();
        ServerMap.ServerCell cell = this.getCell(cx, cy);
        if (cell != null) {
            IsoChunk c = cell.chunks[chx][chy];
            if (c != null) {
                c.setSquare(sqx, sqy, z, sq);
            }
        }
    }

    public IsoChunk getChunk(int wx, int wy) {
        int cx = this.worldChunkToServerCellXY(wx);
        int cy = this.worldChunkToServerCellXY(wy);
        int chx = (wx - cx * 8) % 8;
        int chy = (wy - cy * 8) % 8;
        cx -= this.getMinX();
        cy -= this.getMinY();
        ServerMap.ServerCell cell = this.getCell(cx, cy);
        return cell != null && (cell.isPublished() || cell.isFinalizingOnCurrentThread()) ? cell.chunks[chx][chy] : null;
    }

    /**
     * Moving-object buckets may outlive a cell transition by one frame. Vehicles
     * from a cell that has not completed publication must never enter
     * BaseVehicle.update(): its chunk/square/list links are established by the
     * vehicle loader only after the cell is published.
     */
    public boolean isVehicleUpdateReady(BaseVehicle vehicle) {
        // ServerMap is not initialized by a pure client/local client world. Client
        // vehicle streaming has its own lifecycle and must retain vanilla updates.
        if (!GameServer.server || this.grid == null || this.cellMap == null) {
            return true;
        }

        if (vehicle == null || !vehicle.addedToWorld || vehicle.chunk == null) {
            return false;
        }

        int cx = this.worldChunkToServerCellXY(vehicle.chunk.wx) - this.getMinX();
        int cy = this.worldChunkToServerCellXY(vehicle.chunk.wy) - this.getMinY();
        ServerCell cell = this.getCell(cx, cy);
        return cell != null && cell.isPublished();
    }

    public void setSoftResetChunk(IsoChunk chunk) {
        int cx = this.worldChunkToServerCellXY(chunk.wx) - this.getMinX();
        int cy = this.worldChunkToServerCellXY(chunk.wy) - this.getMinY();
        if (!this.isInvalidCell(cx, cy)) {
            ServerMap.ServerCell cell = this.getCell(cx, cy);
            if (cell == null) {
                cell = new ServerMap.ServerCell();
                cell.markPublished();
                this.cellMap[cy * this.width + cx] = cell;
            }

            int chx = (chunk.wx - cx * 8) % 8;
            int chy = (chunk.wy - cy * 8) % 8;
            cell.chunks[chx][chy] = chunk;
        }
    }

    public void clearSoftResetChunk(IsoChunk chunk) {
        int cx = this.worldChunkToServerCellXY(chunk.wx) - this.getMinX();
        int cy = this.worldChunkToServerCellXY(chunk.wy) - this.getMinY();
        ServerMap.ServerCell cell = this.getCell(cx, cy);
        if (cell != null) {
            int chx = (chunk.wx - cx * 8) % 8;
            int chy = (chunk.wy - cy * 8) % 8;
            cell.chunks[chx][chy] = null;
        }
    }

    private static final class DistToCellComparator implements Comparator<ServerMap.ServerCell> {
        private final Vector2[] pos = new Vector2[1024];
        private int posCount;

        public DistToCellComparator() {
            for (int i = 0; i < this.pos.length; i++) {
                this.pos[i] = new Vector2();
            }
        }

        public void init() {
            this.posCount = 0;

            for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
                UdpConnection c = GameServer.udpEngine.connections.get(n);
                if (c.isFullyConnected()) {
                    for (int playerIndex = 0; playerIndex < 4; playerIndex++) {
                        if (c.players[playerIndex] != null) {
                            this.pos[this.posCount].set(c.players[playerIndex].getX(), c.players[playerIndex].getY());
                            this.posCount++;
                        }
                    }
                }
            }
        }

        public int compare(ServerMap.ServerCell a, ServerMap.ServerCell b) {
            float aScore = Float.MAX_VALUE;
            float bScore = Float.MAX_VALUE;

            for (int i = 0; i < this.posCount; i++) {
                float x = this.pos[i].x;
                float y = this.pos[i].y;
                aScore = Math.min(aScore, this.distToCell(x, y, a));
                bScore = Math.min(bScore, this.distToCell(x, y, b));
            }

            return Float.compare(aScore, bScore);
        }

        private float distToCell(float x, float y, ServerMap.ServerCell cell) {
            int minX = cell.wx * 64;
            int minY = cell.wy * 64;
            int maxX = minX + 64;
            int maxY = minY + 64;
            float closestX = x;
            float closestY = y;
            if (x < minX) {
                closestX = minX;
            } else if (x > maxX) {
                closestX = maxX;
            }

            if (y < minY) {
                closestY = minY;
            } else if (y > maxY) {
                closestY = maxY;
            }

            return IsoUtils.DistanceToSquared(x, y, closestX, closestY);
        }
    }

    private static enum EThreadCommand {
        SaveCell,
        Quit;
    }

    public static final class ServerCell {
        public static enum LoadState {
            QUEUED,
            FINALIZING,
            READY_FOR_MAIN_THREAD_GRID_LOAD,
            READY_TO_PUBLISH,
            PUBLISHED,
            CANCELLED,
            FAILED,
            UNLOADING,
            UNLOADED;
        }

        public int wx;
        public int wy;
        public volatile boolean isLoaded;
        private volatile LoadState loadState = LoadState.QUEUED;
        private volatile long finalizingThreadId;
        private int mainThreadGridLoadCursor;
        private int pendingChunkUnloads;
        private int completedChunkUnloads;
        private int failedChunkUnloads;
        public boolean physicsCheck;
        public final IsoChunk[][] chunks = new IsoChunk[8][8];
        private final HashSet<RoomDef> unexploredRooms = new HashSet<>();
        private static final ServerChunkLoader chunkLoader = new ServerChunkLoader();
        private static final ArrayList<ServerMap.ServerCell> loaded = new ArrayList<>();
        private boolean startedLoading;
        public volatile boolean cancelLoading;
        public boolean loadingWasCancelled;
        private static final ArrayList<ServerMap.ServerCell> loaded2 = new ArrayList<>();
        private boolean doingRecalc;
        private final UpdateLimit hotSaveFrequency = new UpdateLimit(1000L);

        public boolean Load2() {
            chunkLoader.getRecalc(loaded2);

            for (int i = 0; i < loaded2.size(); i++) {
                if (loaded2.get(i) == this) {
                    loaded2.remove(i);
                    this.finalizeLoad();
                    return true;
                }
            }

            return false;
        }

        private synchronized void finalizeLoad() {
            if (this.cancelLoading || this.loadState != LoadState.QUEUED) {
                return;
            }

            this.loadState = LoadState.FINALIZING;
            CompletableFuture.runAsync(this::finalizeLoadAsync, PZForkJoinPool.commonPool());
        }

        private void finalizeLoadAsync() {
            this.finalizingThreadId = Thread.currentThread().getId();
            try {
                if (this.cancelLoading) {
                    this.loadState = LoadState.CANCELLED;
                    return;
                }

                long start = System.nanoTime();
                this.RecalcAll2();
                if (this.cancelLoading) {
                    this.loadState = LoadState.CANCELLED;
                    return;
                }

                if (ServerMap.mapLoading) {
                    DebugType.MapLoading.debugln("loaded2=" + loaded2);
                    DebugType.MapLoading.debugln(
                            "worker finished cell " + this.wx + "," + this.wy + " ms="
                                    + (System.nanoTime() - start) / 1000000.0F);
                }

                this.loadState = LoadState.READY_FOR_MAIN_THREAD_GRID_LOAD;
            } catch (Throwable t) {
                this.loadState = LoadState.FAILED;
                this.cancelLoading = true;
                DebugType.General.printException(t, LogSeverity.Error);
                return;
            } finally {
                this.finalizingThreadId = 0L;
            }

            // No live-world structure may observe this cell until preupdate()
            // finishes its Lua-capable grid-load phase on the main thread.
            ServerMap.instance.readyForMainThreadGridLoad.add(this);
        }

        public boolean isPublished() {
            return this.isLoaded && this.loadState == LoadState.PUBLISHED;
        }

        public boolean isUnloading() {
            return this.loadState == LoadState.UNLOADING;
        }

        private boolean isFinalizingOnCurrentThread() {
            return this.loadState == LoadState.FINALIZING
                    && this.finalizingThreadId == Thread.currentThread().getId();
        }

        private boolean publishLoad() {
            if (this.cancelLoading || this.loadState != LoadState.READY_TO_PUBLISH) {
                this.loadState = LoadState.CANCELLED;
                return false;
            }

            this.isLoaded = true;
            this.loadState = LoadState.PUBLISHED;
            return true;
        }

        /**
         * Executes exactly one non-null chunk's doLoadGridsquare() call. This is
         * deliberately main-thread-only because ItemPicker can invoke Kahlua
         * callbacks while generating container contents.
         *
         * @return true once every chunk in the cell has run the grid-load phase
         */
        private boolean doNextMainThreadGridLoad() {
            while (this.mainThreadGridLoadCursor < 64) {
                int x = this.mainThreadGridLoadCursor / 8;
                int y = this.mainThreadGridLoadCursor % 8;
                this.mainThreadGridLoadCursor++;
                IsoChunk chunk = this.chunks[x][y];
                if (chunk == null) {
                    continue;
                }

                long startNanos = System.nanoTime();
                chunk.doLoadGridsquare();
                ApocBRServerTelemetry.recordServerMapLoadCommitPhase(
                        "gridLoad", 1, System.nanoTime() - startNanos);
                return false;
            }

            return true;
        }

        private void finishMainThreadGridLoad() {
            long phaseStart = System.nanoTime();
            int indoorRooms = 0;
            for (RoomDef def : this.unexploredRooms) {
                indoorRooms++;
                def.indoorZombies++;
                if (def.indoorZombies == 1) {
                    try {
                        VirtualZombieManager.instance.tryAddIndoorZombies(def, false);
                    } catch (Exception var15) {
                        DebugType.General.printException(var15, LogSeverity.Error);
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapLoadCommitPhase("indoorZombies", indoorRooms,
                    System.nanoTime() - phaseStart);
            this.loadState = LoadState.READY_TO_PUBLISH;
        }

        private void markPublished() {
            this.isLoaded = true;
            this.loadState = LoadState.PUBLISHED;
        }

        private void loadVehicles() {
            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = this.chunks[cx][cy];
                    if (chunk != null && !chunk.isNewChunk()) {
                        VehiclesDB2.instance.loadChunkMain(chunk);
                    }
                }
            }
        }

        public void RecalcAll2() {
            long phaseStart = System.nanoTime();
            int sx = this.wx * 8 * 8;
            int sy = this.wy * 8 * 8;
            int ex = sx + 64;
            int ey = sy + 64;

            for (RoomDef def : this.unexploredRooms) {
                def.indoorZombies--;
            }

            this.unexploredRooms.clear();
            int minLevel = Integer.MAX_VALUE;
            int maxLevel = Integer.MIN_VALUE;

            for (int chunkY = 0; chunkY < 8; chunkY++) {
                for (int chunkX = 0; chunkX < 8; chunkX++) {
                    IsoChunk chunk = this.getChunk(chunkX, chunkY);
                    if (chunk != null) {
                        minLevel = PZMath.min(minLevel, chunk.getMinLevel());
                        maxLevel = PZMath.max(maxLevel, chunk.getMaxLevel());
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapLoadCommitPhase("publish", 64, System.nanoTime() - phaseStart);

            phaseStart = System.nanoTime();
            int borderSurroundCalls = 0;
            for (int z = 1; z <= maxLevel; z++) {
                for (int x = -1; x < 65; x++) {
                    IsoGridSquare sq = ServerMap.instance.getGridSquare(sx + x, sy - 1, z);
                    if (sq != null && !sq.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                        borderSurroundCalls++;
                    } else if (x >= 0 && x < 64) {
                        sq = ServerMap.instance.getGridSquare(sx + x, sy, z);
                        if (sq != null && !sq.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                            borderSurroundCalls++;
                        }
                    }

                    sq = ServerMap.instance.getGridSquare(sx + x, sy + 64, z);
                    if (sq != null && !sq.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                        borderSurroundCalls++;
                    } else if (x >= 0 && x < 64) {
                        ServerMap.instance.getGridSquare(sx + x, sy + 64 - 1, z);
                        if (sq != null && !sq.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                            borderSurroundCalls++;
                        }
                    }
                }

                for (int y = 0; y < 64; y++) {
                    IsoGridSquare sqx = ServerMap.instance.getGridSquare(sx - 1, sy + y, z);
                    if (sqx != null && !sqx.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                        borderSurroundCalls++;
                    } else {
                        sqx = ServerMap.instance.getGridSquare(sx, sy + y, z);
                        if (sqx != null && !sqx.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                            borderSurroundCalls++;
                        }
                    }

                    sqx = ServerMap.instance.getGridSquare(sx + 64, sy + y, z);
                    if (sqx != null && !sqx.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                        borderSurroundCalls++;
                    } else {
                        sqx = ServerMap.instance.getGridSquare(sx + 64 - 1, sy + y, z);
                        if (sqx != null && !sqx.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                            borderSurroundCalls++;
                        }
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapLoadCommitPhase("borderSurround", borderSurroundCalls,
                    System.nanoTime() - phaseStart);

            phaseStart = System.nanoTime();
            int borderRecalcCalls = 0;
            for (int z = minLevel; z <= maxLevel; z++) {
                for (int x = 0; x < 64; x++) {
                    IsoGridSquare sqxx = ServerMap.instance.getGridSquare(sx + x, sy, z);
                    if (sqxx != null) {
                        sqxx.RecalcAllWithNeighbours(true);
                        borderRecalcCalls++;
                    }

                    sqxx = ServerMap.instance.getGridSquare(sx + x, ey - 1, z);
                    if (sqxx != null) {
                        sqxx.RecalcAllWithNeighbours(true);
                        borderRecalcCalls++;
                    }
                }

                for (int y = 0; y < 64; y++) {
                    IsoGridSquare sqxxx = ServerMap.instance.getGridSquare(sx, sy + y, z);
                    if (sqxxx != null) {
                        sqxxx.RecalcAllWithNeighbours(true);
                        borderRecalcCalls++;
                    }

                    sqxxx = ServerMap.instance.getGridSquare(ex - 1, sy + y, z);
                    if (sqxxx != null) {
                        sqxxx.RecalcAllWithNeighbours(true);
                        borderRecalcCalls++;
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapLoadCommitPhase("borderRecalc", borderRecalcCalls,
                    System.nanoTime() - phaseStart);

            phaseStart = System.nanoTime();
            int propertySquares = 0;
            int nSquares = 64;

            for (int cx = 0; cx < 8; cx++) {
                for (int cy = 0; cy < 8; cy++) {
                    IsoChunk chunk = this.chunks[cx][cy];
                    if (chunk != null) {
                        chunk.loaded = true;

                        for (int i = 0; i < 64; i++) {
                            for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
                                int squaresIndexOfLevel = chunk.squaresIndexOfLevel(z);
                                IsoGridSquare g = chunk.squares[squaresIndexOfLevel][i];
                                if (g != null) {
                                    if (g.getRoom() != null && !g.getRoom().def.explored) {
                                        this.unexploredRooms.add(g.getRoom().def);
                                    }

                                    g.propertiesDirty = true;
                                    propertySquares++;
                                }
                            }
                        }
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapLoadCommitPhase("chunkFlags", propertySquares,
                    System.nanoTime() - phaseStart);

        }

        public void Unload() {
            if (this.isPublished()) {
                this.loadState = LoadState.UNLOADING;
                this.pendingChunkUnloads = 0;
                this.completedChunkUnloads = 0;
                this.failedChunkUnloads = 0;
                if (ServerMap.mapLoading) {
                    DebugType.MapLoading
                            .debugln(
                                    "Unloading cell: "
                                            + this.wx
                                            + ", "
                                            + this.wy
                                            + " ("
                                            + ServerMap.instance.toWorldCellX(this.wx)
                                            + ", "
                                            + ServerMap.instance.toWorldCellY(this.wy)
                                            + ")");
                }

                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        IsoChunk chunk = this.chunks[x][y];
                        if (chunk != null) {
                            final int unloadX = x;
                            final int unloadY = y;
                            final IsoChunk unloadChunk = chunk;
                            this.pendingChunkUnloads++;
                            CompletableFuture<IsoChunk> future = unloadChunk.removeFromWorldAsyncJob();
                            future.whenComplete((completedChunk, error) ->
                                    ServerMap.instance.completedChunkUnloads.add(
                                            new CompletedChunkUnload(this, unloadX, unloadY, unloadChunk, error)));
                        }
                    }
                }

                if (this.pendingChunkUnloads == 0) {
                    this.completeUnloadState();
                }
            }
        }

        private synchronized void finishChunkUnload(int x, int y, IsoChunk chunk, Throwable error) {
            if (this.loadState != LoadState.UNLOADING) {
                return;
            }

            if (error != null) {
                this.failedChunkUnloads++;
                DebugType.General.printException(error, LogSeverity.Error);
            } else if (chunk != null) {
                chunk.loadVehiclesObject = null;

                long vehicleSaveStartNanos = System.nanoTime();
                ArrayList<BaseVehicle> vehicles = new ArrayList<>();
                try {
                    for (int i = 0; i < chunk.vehicles.size(); i++) {
                        BaseVehicle vehicle = chunk.vehicles.get(i);
                        if (vehicle != null) {
                            vehicles.add(vehicle);
                        }
                    }
                } catch (Throwable t) {
                    DebugType.General.printException(t, LogSeverity.Error);
                }

                for (int i = 0; i < vehicles.size(); i++) {
                    try {
                        VehiclesDB2.instance.updateVehicle(vehicles.get(i));
                    } catch (Throwable t) {
                        DebugType.General.printException(t, LogSeverity.Error);
                    }
                }
                ApocBRServerTelemetry.recordServerMapUnloadPhase(
                        "vehicleSave", vehicles.size(), System.nanoTime() - vehicleSaveStartNanos);

                long saveEnqueueStartNanos = System.nanoTime();
                chunkLoader.addSaveUnloadedJob(chunk);
                ApocBRServerTelemetry.recordServerMapUnloadPhase(
                        "saveEnqueue", 1, System.nanoTime() - saveEnqueueStartNanos);

                if (x >= 0 && x < 8 && y >= 0 && y < 8 && this.chunks[x][y] == chunk) {
                    this.chunks[x][y] = null;
                }
            }

            this.completedChunkUnloads++;
            if (this.completedChunkUnloads >= this.pendingChunkUnloads) {
                if (this.failedChunkUnloads == 0) {
                    this.completeUnloadState();
                } else {
                    // Keep the cell in UNLOADING instead of marking it safe: at least one
                    // chunk did not complete teardown/save handoff, so ServerMap must not
                    // detach/null the cell as if persistence succeeded.
                    this.loadState = LoadState.UNLOADING;
                }
            }
        }

        private void completeUnloadState() {
            if (this.loadState == LoadState.UNLOADED) {
                return;
            }

            if (this.isLoaded) {
                for (RoomDef def : this.unexploredRooms) {
                    def.indoorZombies--;
                }
            }

            this.isLoaded = false;
            this.loadState = LoadState.UNLOADED;
        }

        private boolean isUnloadFinished() {
            return this.loadState == LoadState.UNLOADED;
        }

        public void Save(boolean worker) {
            if (this.isLoaded) {
                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        IsoChunk chunk = this.chunks[x][y];
                        if (chunk != null) {
                            try {
                                chunkLoader.addSaveLoadedJob(chunk);
                            } catch (Exception var6) {
                                DebugType.General.printException(var6, LogSeverity.Error);
                                LoggerManager.getLogger("map").write(var6);
                            }
                        }
                    }
                }

                if (!worker) {
                    chunkLoader.updateSaved();
                }
            }
        }

        public void UpdateVehicle() {
            if (this.isLoaded) {
                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        IsoChunk chunk = this.chunks[x][y];
                        if (chunk != null) {
                            try {
                                for (int i = 0; i < chunk.vehicles.size(); i++) {
                                    BaseVehicle vehicle = chunk.vehicles.get(i);
                                    VehiclesDB2.instance.updateVehicle(vehicle);
                                }
                            } catch (Exception var6) {
                                DebugType.General.printException(var6, LogSeverity.Error);
                                LoggerManager.getLogger("map").write(var6);
                            }
                        }
                    }
                }
            }
        }

        public void saveChunk(IsoChunk chunk) {
            if (this.isLoaded) {
                if (chunk != null) {
                    chunkLoader.addSaveLoadedJob(chunk);
                }
            }
        }

        public void update() {
            boolean shouldProcessHotSaves = !GameServer.server && this.hotSaveFrequency.Check();

            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = this.chunks[x][y];
                    if (chunk != null) {
                        chunk.update();
                        if (shouldProcessHotSaves && chunk.requiresHotSave) {
                            this.saveChunk(chunk);
                            chunk.requiresHotSave = false;
                        }
                    }
                }
            }

            this.physicsCheck = false;
        }

        public IsoChunk getChunk(int x, int y) {
            if (x >= 0 && x < 8 && y >= 0 && y < 8) {
                IsoChunk chunk = this.chunks[x][y];
                if (chunk != null) {
                    return chunk;
                }
            }

            return null;
        }

        public int getWX() {
            return this.wx;
        }

        public int getWY() {
            return this.wy;
        }
    }

    public final class WorkerThread extends Thread {
        boolean quit;
        final LinkedBlockingQueue<ServerMap.WorkerThreadCommand> commandQ;

        public WorkerThread() {
            Objects.requireNonNull(ServerMap.this);
            super();
            this.commandQ = new LinkedBlockingQueue<>();
        }

        @Override
        public void run() {
            while (!this.quit) {
                try {
                    this.runInner();
                } catch (Exception var2) {
                    DebugType.General.printException(var2, LogSeverity.Error);
                }
            }
        }

        private void runInner() throws InterruptedException, IOException {
            ServerMap.WorkerThreadCommand command = this.commandQ.take();
            switch (command.e) {
                case SaveCell:
                    command.cell.Save(true);
                    break;
                case Quit:
                    this.quit = true;
            }
        }

        void putCommand(ServerMap.EThreadCommand e, ServerMap.ServerCell cell) {
            ServerMap.WorkerThreadCommand command = new ServerMap.WorkerThreadCommand();
            command.e = e;
            command.cell = cell;

            while (true) {
                try {
                    this.commandQ.put(command);
                    return;
                } catch (InterruptedException var5) {
                }
            }
        }
    }

    private static final class WorkerThreadCommand {
        ServerMap.EThreadCommand e;
        ServerMap.ServerCell cell;
    }
}
