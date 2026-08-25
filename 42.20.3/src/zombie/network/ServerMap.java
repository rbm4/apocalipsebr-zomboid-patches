// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import zombie.ApocBRMainThreadOrchestrator;
import zombie.ApocBRServerTelemetry;
import zombie.core.logger.ExceptionLogger;
import zombie.GameTime;
import zombie.iso.objects.IsoGenerator;
import zombie.LootRespawn;
import zombie.MapCollisionData;
import zombie.ReanimatedPlayers;
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
import zombie.network.id.ObjectIDManager;
import zombie.network.packets.ChunkObjectStateRequestPacket;
import zombie.network.packets.INetworkPacket;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.ZombiePopulationManager;
import zombie.radio.ZomboidRadio;
import zombie.savefile.ServerPlayerDB;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclesDB2;
import zombie.world.moddata.GlobalModData;
import zombie.worldMap.WorldMapVisitedServer;
import zombie.worldMap.network.HiddenAuthors;
import zombie.worldMap.network.WorldMapServer;

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

    public static void runLoad2MainThreadTask(String label, Runnable task) {
        if (task == null) {
            return;
        }

        if (!GameServer.server || GameServer.mainThread == null) {
            task.run();
            return;
        }

        ServerMap.ServerCell.load2MainThread.submitAndWait(label, task);
    }

    public static void submitLoad2MainThreadTask(String label, Runnable task) {
        if (task == null) {
            return;
        }

        if (!GameServer.server || GameServer.mainThread == null) {
            task.run();
            return;
        }

        ServerMap.ServerCell.load2MainThreadDeferred.submit(label, task);
    }

    public static void runLoad2ChunkRegistrations(IsoChunk chunk) {
        if (chunk == null) {
            return;
        }

        ServerMap.runLoad2ChunkRegistrations(Collections.singletonList(chunk));
    }

    public static void runLoad2ChunkRegistrations(List<IsoChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        if (!GameServer.server || GameServer.mainThread == null) {
            ServerMap.ServerCell.runLoad2ChunkRegistrationsOnMainThread(chunks);
            return;
        }

        ArrayList<IsoChunk> batch = new ArrayList<>(chunks);
        ServerMap.ServerCell.load2MainThread.submitAndWait(
            "IsoChunk.nativeChunkRegistrationBatch",
            () -> ServerMap.ServerCell.runLoad2ChunkRegistrationsOnMainThread(batch)
        );
    }

    private static final int SAVE_CELL_WORK_THREADS = 1;
    private static final ServerMap.WorkerThread[] workerThreads = new ServerMap.WorkerThread[SAVE_CELL_WORK_THREADS];
    public boolean queuedSaveAll;
    public boolean queuedQuit;
    public static ServerMap instance = new ServerMap();
    public ServerMap.ServerCell[] cellMap;
    public ArrayList<ServerMap.ServerCell> loadedCells = new ArrayList<>();
    public ArrayList<ServerMap.ServerCell> releventNow = new ArrayList<>();
    private final ArrayList<ServerMap.ServerCell> deferredUnloadCells = new ArrayList<>();
    private int deferredUnloadQueuedThisTick;
    private long deferredUnloadTick;
    int width;
    int height;
    IsoMetaGrid grid;
    ArrayList<ServerMap.ServerCell> toLoad = new ArrayList<>();
    static final ServerMap.DistToCellComparator distToCellComparator = new ServerMap.DistToCellComparator();
    private final ArrayList<ServerMap.ServerCell> tempCells = new ArrayList<>();
    long lastTick;

    public short getUniqueZombieId() {
        return this.zombieMap.allocateID();
    }

    public void SaveAll() {
        long start = System.nanoTime();
        this.drainDeferredUnloadsForSave();
        if (!GameServer.softReset && this.loadedCells.size() >= 10) {
            for (int i = 0; i < SAVE_CELL_WORK_THREADS; i++) {
                workerThreads[i] = new ServerMap.WorkerThread();
                workerThreads[i].setDaemon(true);
                workerThreads[i].start();
            }

            for (int n = 0; n < this.loadedCells.size(); n++) {
                ServerMap.ServerCell cell = this.loadedCells.get(n);
                workerThreads[n % SAVE_CELL_WORK_THREADS].putCommand(ServerMap.EThreadCommand.SaveCell, cell);
                cell.UpdateVehicle();
            }

            for (int i = 0; i < SAVE_CELL_WORK_THREADS; i++) {
                workerThreads[i].putCommand(ServerMap.EThreadCommand.Quit, null);
            }

            while (true) {
                boolean running = false;

                for (int i = 0; i < SAVE_CELL_WORK_THREADS; i++) {
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

    private ServerMap.ServerCell getDeferredUnloadCell(int x, int y) {
        int wx = x + this.getMinX();
        int wy = y + this.getMinY();

        for (int i = 0; i < this.deferredUnloadCells.size(); i++) {
            ServerMap.ServerCell cell = this.deferredUnloadCells.get(i);
            if (cell.wx == wx && cell.wy == wy) {
                return cell;
            }
        }

        return null;
    }

    public boolean isInvalidCell(int x, int y) {
        return x < 0 || y < 0 || x >= this.width || y >= this.height;
    }

    public void loadOrKeepRelevent(int x, int y) {
        if (!this.isInvalidCell(x, y)) {
            ServerMap.ServerCell cell = this.getCell(x, y);
            if (cell == null) {
                if (this.getDeferredUnloadCell(x, y) != null) {
                    return;
                }

                cell = new ServerMap.ServerCell();
                cell.wx = x + this.getMinX();
                cell.wy = y + this.getMinY();
                if (cell.wx == -1 && cell.wy == -1) {
                    return;
                }

                if (mapLoading) {
                    DebugType.MapLoading
                        .debugln("Loading cell: " + cell.wx + ", " + cell.wy + " (" + this.toWorldCellX(cell.wx) + ", " + this.toWorldCellY(cell.wy) + ")");
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
        int cx = PZMath.fastfloor((float)x);
        int cy = PZMath.fastfloor((float)y);
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
        if (PathfindNative.useNativeCode) {
            PathfindNative.instance.stop();
            PathfindNative.freeMemoryAtExit();
        } else {
            PolygonalMap2.instance.stop();
        }
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
        this.saveQuitFlag = quit;
        this.saveClientPaused = false;
        this.saveStartTime = System.nanoTime();
        this.SaveAll();
        this.checkClientPause();
        ServerPlayerDB.getInstance().save();
        this.checkClientPause();
        WorldMapVisitedServer.getInstance().save();
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

        this.checkClientPause();
        HiddenAuthors.write();
        System.out.println("Saving finish");
        DebugLog.log("Saving took " + (System.nanoTime() - this.saveStartTime) / 1000000.0 + " ms");
    }

    public void preupdate() {
        long apocBrSectionStart = ApocBRServerTelemetry.beginDetail();
        long apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
        int apocBrUnits = 0;
        this.lastTick = System.nanoTime();
        mapLoading = DebugType.MapLoading.isEnabled();

        for (int i = 0; i < this.toLoad.size(); i++) {
            ServerMap.ServerCell cell = this.toLoad.get(i);
            if (cell.loadingWasCancelled) {
                apocBrUnits++;
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
                this.toLoad.remove(i--);
            }
        }

        ApocBRServerTelemetry.recordServerMapPrePhaseSince("cancelScan", apocBrUnits, apocBrPhaseStart);
        ApocBRServerTelemetry.recordServerMapPreQueues(
            ServerMap.ServerCell.chunkLoader.getLoadQueueSize(),
            ServerMap.ServerCell.chunkLoader.getLoadedQueueSize(),
            ServerMap.ServerCell.chunkLoader.getRecalcQueueSize(),
            ServerMap.ServerCell.chunkLoader.getRecalcDoneQueueSize(),
            ServerMap.ServerCell.chunkLoader.getSaveQueueSize()
        );

        if (!this.toLoad.isEmpty()) {
            this.tempCells.clear();

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            for (int ixxx = 0; ixxx < this.toLoad.size(); ixxx++) {
                ServerMap.ServerCell cell = this.toLoad.get(ixxx);
                if (!cell.cancelLoading && !cell.startedLoading) {
                    this.tempCells.add(cell);
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("collectPendingLoads", this.tempCells.size(), apocBrPhaseStart);

            if (!this.tempCells.isEmpty()) {
                apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                distToCellComparator.init();
                this.tempCells.sort(distToCellComparator);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("sortPendingLoads", this.tempCells.size(), apocBrPhaseStart);

                apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                for (int ixxxx = 0; ixxxx < this.tempCells.size(); ixxxx++) {
                    ServerMap.ServerCell cell = this.tempCells.get(ixxxx);
                    ServerMap.ServerCell.chunkLoader.addJob(cell);
                    cell.startedLoading = true;
                }
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("addLoadJobs", this.tempCells.size(), apocBrPhaseStart);
            }

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            ServerMap.ServerCell.chunkLoader.getLoaded(ServerMap.ServerCell.loaded);
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("drainLoaded", ServerMap.ServerCell.loaded.size(), apocBrPhaseStart);

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
            for (int ixxxx = 0; ixxxx < ServerMap.ServerCell.loaded.size(); ixxxx++) {
                ServerMap.ServerCell cell = ServerMap.ServerCell.loaded.get(ixxxx);
                if (!cell.doingRecalc) {
                    ServerMap.ServerCell.chunkLoader.addRecalcJob(cell);
                    cell.doingRecalc = true;
                    apocBrUnits++;
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("addRecalcJobs", apocBrUnits, apocBrPhaseStart);

            ServerMap.ServerCell.loaded.clear();
            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            ServerMap.ServerCell.chunkLoader.getRecalc(ServerMap.ServerCell.loaded2);
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("drainRecalc", ServerMap.ServerCell.loaded2.size(), apocBrPhaseStart);
            // ApocBR: load2 advances a slice per tick instead of running to completion in one call.
            // Cells that become ready while a job is in flight accumulate in loaded2 and are admitted
            // to the next job - they cannot join the running one without breaking its colour
            // partition, and waiting one job cycle is cheaper than a stall. LOS is no longer suspended
            // around this: ServerLOS skips cells flagged loadInProgress instead, so it keeps running
            // for the rest of the world while these cells build.
            if (ServerMap.ServerCell.load2Job == null && !ServerMap.ServerCell.loaded2.isEmpty()) {
                ServerMap.ServerCell.load2Job = new ServerMap.ServerCell.Load2Job(ServerMap.ServerCell.loaded2);
                ServerMap.ServerCell.loaded2.clear();
            }

            if (ServerMap.ServerCell.load2Job != null) {
                apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                apocBrUnits = ServerMap.ServerCell.load2Job.getCells().size();
                boolean load2Done = ServerMap.ServerCell.load2Job.advance(ServerMap.ServerCell.LOAD2_MAX_NANOS_PER_TICK);

                // load2 is now one slice per tick, so "calls" counts slices and only the slice that
                // retires the job may report the cell count - charging it on every slice would
                // multiply the cell total by however many ticks the job happened to span.
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2", load2Done ? apocBrUnits : 0, apocBrPhaseStart);

                if (load2Done) {
                    long apocBrRemoveStart = ApocBRServerTelemetry.beginDetail();
                    this.retireLoad2Job(ServerMap.ServerCell.load2Job);
                    ServerMap.ServerCell.load2Job = null;
                    ApocBRServerTelemetry.recordServerMapPrePhaseSince("removeLoaded2FromToLoad", apocBrUnits, apocBrRemoveStart);
                }
            }
            ApocBRServerTelemetry.recordServerMapPreQueues(
                ServerMap.ServerCell.chunkLoader.getLoadQueueSize(),
                ServerMap.ServerCell.chunkLoader.getLoadedQueueSize(),
                ServerMap.ServerCell.chunkLoader.getRecalcQueueSize(),
                ServerMap.ServerCell.chunkLoader.getRecalcDoneQueueSize(),
                ServerMap.ServerCell.chunkLoader.getSaveQueueSize()
            );
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
            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            this.QueuedSaveAll(false);
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("saveAll", 1, apocBrPhaseStart);
        }

        if (this.queuedQuit) {
            System.exit(0);
        }

        this.releventNow.clear();
        this.updateLosThisFrame = LOS_TICK.Check();
        if (TIME_TICK.Check()) {
            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            ServerMap.ServerCell.chunkLoader.saveLater(GameTime.instance);
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("saveLater", 1, apocBrPhaseStart);
        }

        if (GameEntityManager.needSave && this.metaEntitySaveFrequency.Check()) {
            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            GameEntityManager.Save();
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("entitySave", 1, apocBrPhaseStart);
        }

        ApocBRServerTelemetry.recordTickSectionSince("serverMapPre", apocBrSectionStart);
    }

    /**
     * ApocBR: end-of-job bookkeeping that used to sit inline in preupdate(), lifted out so both the
     * in-tick and idle-window drivers can retire a finished job.
     */
    private void retireLoad2Job(ServerMap.ServerCell.Load2Job job) {
        // units = slices the job consumed, so units/calls is "slices to load a cell group" and avgMs
        // is wall time per job. Those two together say whether slicing is keeping up with demand.
        ApocBRServerTelemetry.recordServerMapPrePhase("load2JobComplete", job.getSlices(), job.getElapsedNanos());

        for (ServerMap.ServerCell cell : job.getCells()) {
            this.toLoad.remove(cell);
            if (cell.loadingWasCancelled && !cell.isLoaded) {
                int cx = cell.wx - this.getMinX();
                int cy = cell.wy - this.getMinY();
                if (!this.isInvalidCell(cx, cy) && this.cellMap[cx + cy * this.width] == cell) {
                    this.cellMap[cx + cy * this.width] = null;
                }

                this.loadedCells.remove(cell);
                this.releventNow.remove(cell);
            }
        }
    }

    /**
     * ApocBR: load2 counterpart to {@link #processDeferredUnloadsInIdleWindow(long)}.
     *
     * The main loop sleeps out the remainder of every cycle it finishes early (throttleSleep averaged
     * 5.9-21.2ms per tick in telemetry). Draining load2 handoffs there costs nothing that was being
     * used for anything else and lets a cell finish sooner without taking a single millisecond away
     * from the tick itself.
     *
     * @return nanoseconds consumed, so the caller can charge it against the same idle budget.
     */
    public long advanceLoad2InIdleWindow(long budgetNanos) {
        ServerMap.ServerCell.Load2Job job = ServerMap.ServerCell.load2Job;
        if (!ServerMap.ServerCell.LOAD2_IDLE_ENABLED || job == null || budgetNanos <= 0L) {
            return 0L;
        }

        long start = System.nanoTime();
        boolean done = job.advance(Math.min(budgetNanos, ServerMap.ServerCell.LOAD2_IDLE_MAX_NANOS));
        long elapsed = System.nanoTime() - start;
        ApocBRServerTelemetry.recordServerMapPrePhase("load2IdleAdvance", done ? job.getCells().size() : 0, elapsed);

        if (done) {
            this.retireLoad2Job(job);
            ServerMap.ServerCell.load2Job = null;
        }

        return elapsed;
    }

    /**
     * ApocBR: tick-phase anchor. Applies whatever load2 workers have handed over since the last
     * anchor, then returns immediately.
     *
     * Without these the only drain points are preupdate() and the idle window, so a worker that hands
     * off a mutation just after preupdate waits a whole tick (~100ms) for it to land, and its chain
     * stalls behind it. Anchors keep the workers fed while the main thread carries on with the tick.
     *
     * Placement is a whitelist, not a sprinkle. These are only safe at top-level tick boundaries where
     * nothing is mid-iteration over a world collection and no global side-band state is set - drained
     * tasks run Lua and can add or remove world objects. Specifically they must NOT go inside
     * MovingObjectUpdateSchedulerUpdateBucket.update() or IsoCell.ProcessIsoObject(), both of which
     * iterate live collections and hold GameTime.perObjectMultiplier at a non-1 value for the whole
     * loop; a task drained in that window would see an 8x or 16x timestep and silently miscompute.
     */
    public static void drainLoad2MainThreadTasks() {
        long start = ApocBRServerTelemetry.beginDetail();
        int applied = ServerMap.ServerCell.load2MainThread.drainAll();
        applied += ServerMap.ServerCell.load2MainThreadDeferred.drainAll();
        if (applied > 0) {
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2Anchor", applied, start);
        }
    }

    private void queueDeferredUnload(ServerMap.ServerCell cell) {
        if (cell == null || !cell.beginDeferredUnload(this.deferredUnloadTick)) {
            return;
        }

        this.deferredUnloadCells.add(cell);
        this.deferredUnloadQueuedThisTick++;
    }

    private boolean hasDeferredUnloads() {
        return !this.deferredUnloadCells.isEmpty();
    }

    private void processDeferredUnloads() {
        this.deferredUnloadTick++;
        this.processDeferredUnloads(
            ServerMap.ServerCell.DEFERRED_UNLOAD_MAX_NANOS_PER_TICK,
            ServerMap.ServerCell.DEFERRED_UNLOAD_MAX_CELLS_PER_TICK,
            ServerMap.ServerCell.DEFERRED_UNLOAD_SLICES_PER_TICK,
            ServerMap.ServerCell.DEFERRED_UNLOAD_SQUARES_PER_SLICE,
            true
        );
    }

    public long processDeferredUnloadsInIdleWindow(long budgetNanos) {
        if (!ServerMap.ServerCell.isDeferredUnloadEnabled()
            || !ServerMap.ServerCell.DEFERRED_UNLOAD_IDLE_ENABLED
            || this.deferredUnloadCells.isEmpty()
            || budgetNanos <= 0L) {
            return 0L;
        }

        long start = System.nanoTime();
        boolean losSuspended = false;

        try {
            ServerLOS.instance.suspend();
            losSuspended = true;
            this.processDeferredUnloads(
                Math.min(budgetNanos, ServerMap.ServerCell.DEFERRED_UNLOAD_IDLE_MAX_NANOS),
                ServerMap.ServerCell.DEFERRED_UNLOAD_IDLE_MAX_CELLS,
                ServerMap.ServerCell.DEFERRED_UNLOAD_IDLE_SLICES,
                ServerMap.ServerCell.DEFERRED_UNLOAD_IDLE_SQUARES_PER_SLICE,
                false
            );
        } finally {
            if (losSuspended) {
                ServerLOS.instance.resume();
            }
        }

        return System.nanoTime() - start;
    }

    private void processDeferredUnloads(long maxNanos, int maxCellsPerTick, int maxSlicesPerTick, int squaresPerSlice, boolean enforceDeadline) {
        int pendingAtStart = this.deferredUnloadCells.size();
        int queued = this.deferredUnloadQueuedThisTick;
        this.deferredUnloadQueuedThisTick = 0;
        if (pendingAtStart == 0) {
            ApocBRServerTelemetry.recordServerMapDeferredUnload(0, queued, 0, 0, 0L, 0L);
            ApocBRServerTelemetry.recordServerMapDeferredUnloadBudget(ServerMap.ServerCell.DEFERRED_UNLOAD_MODE, 0, 0, 0, 0, 0);
            return;
        }

        long start = ApocBRServerTelemetry.beginDetail();
        long deadline = System.nanoTime() + Math.max(1L, maxNanos);
        int maxCells = PZMath.max(1, maxCellsPerTick);
        int maxSlices = PZMath.max(1, maxSlicesPerTick);
        int overdueCells = 0;
        if (enforceDeadline) {
            for (int i = 0; i < this.deferredUnloadCells.size(); i++) {
                if (this.deferredUnloadCells.get(i).getDeferredUnloadAgeTicks(this.deferredUnloadTick) >= ServerMap.ServerCell.DEFERRED_UNLOAD_MAX_TICKS) {
                    overdueCells++;
                }
            }
        }

        int cellsToTouch = PZMath.min(PZMath.max(maxCells, overdueCells), pendingAtStart);
        maxSlices = PZMath.max(maxSlices, overdueCells);
        int attempts = 0;
        int partialCells = 0;
        int unloaded = 0;

        for (int touched = 0; touched < cellsToTouch && attempts < maxSlices && !this.deferredUnloadCells.isEmpty(); touched++) {
            ServerMap.ServerCell cell = this.deferredUnloadCells.remove(0);
            attempts++;
            boolean forced = enforceDeadline && cell.getDeferredUnloadAgeTicks(this.deferredUnloadTick) >= ServerMap.ServerCell.DEFERRED_UNLOAD_MAX_TICKS;
            boolean finished = cell.processDeferredUnloadSlice(forced ? Integer.MAX_VALUE : squaresPerSlice);
            if (finished) {
                unloaded++;
            } else {
                partialCells++;
                this.deferredUnloadCells.add(cell);
            }

            if (!forced && System.nanoTime() >= deadline) {
                break;
            }
        }

        long oldestAgeMs = 0L;
        long now = System.currentTimeMillis();
        for (int i = 0; i < this.deferredUnloadCells.size(); i++) {
            oldestAgeMs = Math.max(oldestAgeMs, now - this.deferredUnloadCells.get(i).getDeferredUnloadQueuedAtMs());
        }

        ApocBRServerTelemetry.recordServerMapDeferredUnload(
            this.deferredUnloadCells.size(),
            queued,
            0,
            unloaded,
            System.nanoTime() - start,
            oldestAgeMs
        );
        ApocBRServerTelemetry.recordServerMapDeferredUnloadBudget(
            ServerMap.ServerCell.DEFERRED_UNLOAD_MODE,
            pendingAtStart,
            maxCells,
            maxSlices,
            attempts,
            partialCells
        );
    }

    private void drainDeferredUnloadsForSave() {
        if (!ServerMap.ServerCell.isDeferredUnloadEnabled() || this.deferredUnloadCells.isEmpty()) {
            return;
        }

        boolean losSuspended = false;

        try {
            ServerLOS.instance.suspend();
            losSuspended = true;
            while (!this.deferredUnloadCells.isEmpty()) {
                ServerMap.ServerCell cell = this.deferredUnloadCells.remove(0);
                while (!cell.processDeferredUnloadSlice(Integer.MAX_VALUE)) {
                }
            }
        } finally {
            if (losSuspended) {
                ServerLOS.instance.resume();
            }
        }
    }

    public void postupdate() {
        long apocBrPostStart = ApocBRServerTelemetry.beginDetail();
        boolean pathfindPaused = false;

        try {
            int apocBrLoadedCellsAtStart = this.loadedCells.size();
            long apocBrLoopStart = ApocBRServerTelemetry.beginDetail();
            long apocBrPhaseStart;
            for (int n = 0; n < this.loadedCells.size(); n++) {
                ServerMap.ServerCell cell = this.loadedCells.get(n);
                apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                boolean relevant = this.releventNow.contains(cell);
                ApocBRServerTelemetry.recordServerMapPostPhaseSince("relevantContains", 1, apocBrPhaseStart);
                boolean outsidePlayerInfluence = false;
                if (!relevant) {
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    outsidePlayerInfluence = this.outsidePlayerInfluence(cell);
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("outsidePlayerInfluence", 1, apocBrPhaseStart);
                }

                boolean shouldBeLoaded = relevant || !outsidePlayerInfluence;
                if (!cell.isLoaded) {
                    if (!shouldBeLoaded && !cell.cancelLoading) {
                        apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                        if (mapLoading) {
                            DebugLog.log(
                                DebugType.MapLoading, "MainThread: cancelling " + cell.wx + "," + cell.wy + " cell.startedLoading=" + cell.startedLoading
                            );
                        }

                        if (!cell.startedLoading) {
                            cell.loadingWasCancelled = true;
                        }

                        cell.cancelLoading = true;
                        ApocBRServerTelemetry.recordServerMapPostPhaseSince("cancelLoading", 1, apocBrPhaseStart);
                    }
                } else if (!shouldBeLoaded) {
                    // ApocBR: a load2 worker may still be building this cell - jobs now span ticks, so
                    // a cell can go irrelevant mid-load. beginDeferredUnload() refuses those, but the
                    // cellMap clear and loadedCells removal below run unconditionally, which would
                    // orphan the cell: still isLoaded, still being written by its worker, but no
                    // longer reachable from cellMap and therefore never unloaded or saved. Skip it
                    // and revisit next tick; loadInProgress clears in the worker's finally.
                    if (cell.loadInProgress) {
                        continue;
                    }

                    int x = cell.wx - this.getMinX();
                    int y = cell.wy - this.getMinY();
                    if (!pathfindPaused) {
                        apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                        ServerLOS.instance.suspend();
                        ApocBRServerTelemetry.recordServerMapPostPhaseSince("losSuspend", 1, apocBrPhaseStart);
                        pathfindPaused = true;
                    }

                    int cellMapIndex = y * this.width + x;
                    ServerMap.ServerCell mapCell = this.cellMap[cellMapIndex];
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    if (ServerMap.ServerCell.isDeferredUnloadEnabled()) {
                        this.queueDeferredUnload(mapCell);
                    } else {
                        mapCell.Unload();
                    }
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("cellUnload", 1, apocBrPhaseStart);
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    this.cellMap[cellMapIndex] = null;
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("cellMapClear", 1, apocBrPhaseStart);
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    this.loadedCells.remove(cell);
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("loadedCellsRemove", 1, apocBrPhaseStart);
                    n--;
                } else {
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    cell.update();
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("cellUpdate", 1, apocBrPhaseStart);
                }
            }

            if (ServerMap.ServerCell.isDeferredUnloadEnabled()) {
                if (this.hasDeferredUnloads() && !pathfindPaused) {
                    apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                    ServerLOS.instance.suspend();
                    ApocBRServerTelemetry.recordServerMapPostPhaseSince("losSuspend", 1, apocBrPhaseStart);
                    pathfindPaused = true;
                }

                this.processDeferredUnloads();
            }
            ApocBRServerTelemetry.recordServerMapPostPhaseSince("loop", apocBrLoadedCellsAtStart, apocBrLoopStart);
        } catch (Exception var10) {
            DebugType.General.printException(var10, LogSeverity.Error);
        } finally {
            if (pathfindPaused) {
                long apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                ServerLOS.instance.resume();
                ApocBRServerTelemetry.recordServerMapPostPhaseSince("losResume", 1, apocBrPhaseStart);
            }
        }

        long apocBrZombiePostStart = ApocBRServerTelemetry.beginDetail();
        NetworkZombiePacker.getInstance().postupdate();
        ApocBRServerTelemetry.recordServerMapPostPhaseSince("zombiePost", 1, apocBrZombiePostStart);
        ApocBRServerTelemetry.recordTickSectionSince("serverMapZombiePost", apocBrZombiePostStart);

        long apocBrChunkStateStart = ApocBRServerTelemetry.beginDetail();
        ChunkObjectStateRequestPacket.processQueue();
        ApocBRServerTelemetry.recordServerMapPostPhaseSince("chunkObjectStatePost", 1, apocBrChunkStateStart);

        long apocBrUpdateSavedStart = ApocBRServerTelemetry.beginDetail();
        ServerMap.ServerCell.chunkLoader.updateSaved();
        ApocBRServerTelemetry.recordServerMapPostPhaseSince("updateSaved", 1, apocBrUpdateSavedStart);
        ApocBRServerTelemetry.recordTickSectionSince("serverMapUpdateSaved", apocBrUpdateSavedStart);
        ApocBRServerTelemetry.recordTickSectionSince("serverMapPost", apocBrPostStart);
    }

    public void physicsCheck(int x, int y) {
        int cx = PZMath.coorddivision(x, 64) - this.getMinX();
        int cy = PZMath.coorddivision(y, 64) - this.getMinY();
        ServerMap.ServerCell cell = this.getCell(cx, cy);
        if (cell != null && cell.isLoaded) {
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
            if (cell != null && cell.isLoaded) {
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
        return cell != null && cell.isLoaded ? cell.chunks[chx][chy] : null;
    }

    public void setSoftResetChunk(IsoChunk chunk) {
        int cx = this.worldChunkToServerCellXY(chunk.wx) - this.getMinX();
        int cy = this.worldChunkToServerCellXY(chunk.wy) - this.getMinY();
        if (!this.isInvalidCell(cx, cy)) {
            ServerMap.ServerCell cell = this.getCell(cx, cy);
            if (cell == null) {
                cell = new ServerMap.ServerCell();
                cell.isLoaded = true;
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
        public int wx;
        public int wy;
        public boolean isLoaded;
        public boolean physicsCheck;
        public final IsoChunk[][] chunks = new IsoChunk[8][8];
        private final HashSet<RoomDef> unexploredRooms = new HashSet<>();
        private static final ServerChunkLoader chunkLoader = new ServerChunkLoader();
        private static final ArrayList<ServerMap.ServerCell> loaded = new ArrayList<>();
        private boolean startedLoading;
        public boolean cancelLoading;
        public boolean loadingWasCancelled;
        /**
         * ApocBR: true from the moment a cell is handed to a load2 worker until its RecalcAll2()
         * has finished, including across tick boundaries.
         *
         * Deliberately NOT consulted by getGridSquare()/getChunk(). RecalcAll2()'s border pass goes
         * EnsureSurroundNotNull() -> IsoCell.createNewGridSquare() -> ServerMap.getChunk(), so gating
         * reads on this flag would make the cell's own border scan create no squares at all and
         * silently corrupt cell seams. Half-built reads are already a legal, handled state: the
         * isLoaded gate returns null and every caller copes.
         *
         * What it does guard is lifecycle transitions that must not run against a cell a worker is
         * still building - deferred unload, and cellMap removal - plus ServerLOS, which uses it to
         * skip mid-load cells instead of the whole LOS subsystem being suspended for the duration of
         * the job.
         */
        public volatile boolean loadInProgress;
        private static final ArrayList<ServerMap.ServerCell> loaded2 = new ArrayList<>();
        private boolean doingRecalc;
        private final UpdateLimit hotSaveFrequency = new UpdateLimit(1000L);
        private static final boolean DEFERRED_UNLOAD_ENABLED = !"false".equalsIgnoreCase(System.getProperty("apocbr.deferredCellUnload", "true"));
        private static final boolean DEFERRED_UNLOAD_ALLOW_COOP = "true".equalsIgnoreCase(System.getProperty("apocbr.deferredCellUnloadInCoop", "false"));
        private static final int DEFERRED_UNLOAD_MODE = DEFERRED_UNLOAD_ENABLED ? 1 : 0;
        private static final int DEFERRED_UNLOAD_MAX_TICKS = Math.max(1, Integer.getInteger("apocbr.unload.maxTicks", 3));
        private static final int DEFERRED_UNLOAD_MAX_MS_PER_TICK = Math.max(1, Integer.getInteger("apocbr.unload.maxMsPerTick", 8));
        private static final long DEFERRED_UNLOAD_MAX_NANOS_PER_TICK = DEFERRED_UNLOAD_MAX_MS_PER_TICK * 1000000L;
        private static final int DEFERRED_UNLOAD_MAX_CELLS_PER_TICK = Math.max(1, Integer.getInteger("apocbr.unload.maxCellsPerTick", 4));
        private static final int DEFERRED_UNLOAD_SLICES_PER_TICK = Math.max(1, Integer.getInteger("apocbr.unload.slicesPerTick", 8));
        private static final int DEFERRED_UNLOAD_SQUARES_PER_SLICE = Math.max(64, Integer.getInteger("apocbr.unload.squaresPerSlice", 1024));
        private static final boolean DEFERRED_UNLOAD_IDLE_ENABLED = !"false".equalsIgnoreCase(System.getProperty("apocbr.unload.idleEnabled", "true"));
        private static final int DEFERRED_UNLOAD_IDLE_MAX_MS = Math.max(1, Integer.getInteger("apocbr.unload.idleMaxMs", 4));
        private static final long DEFERRED_UNLOAD_IDLE_MAX_NANOS = DEFERRED_UNLOAD_IDLE_MAX_MS * 1000000L;
        private static final int DEFERRED_UNLOAD_IDLE_MAX_CELLS = Math.max(1, Integer.getInteger("apocbr.unload.idleMaxCells", 8));
        private static final int DEFERRED_UNLOAD_IDLE_SLICES = Math.max(1, Integer.getInteger("apocbr.unload.idleSlices", 16));
        private static final int DEFERRED_UNLOAD_IDLE_SQUARES_PER_SLICE = Math.max(64, Integer.getInteger("apocbr.unload.idleSquaresPerSlice", 1024));
        /**
         * ApocBR: this waits on other workers' per-chunk hop chains, and every hop in those chains is
         * a submitAndWait() to the main thread. Now that the main thread drains on a budget instead of
         * parking until the group finishes, a chain legitimately takes far longer than a second in
         * wall time. The old 1s default would expire and throw, and RecalcAll2()'s caller turns that
         * into cancelLoading - so the cell would be discarded and reloaded, which is the churn this
         * work exists to remove. Kept in step with COOPERATIVE_TASK_TIMEOUT_NANOS: a liveness guard,
         * not a work-duration bound.
         */
        private static final long LOAD2_CHUNK_FINISH_TIMEOUT_MS = Math.max(1000L, Long.getLong("apocbr.load2ChunkFinishTimeoutMs", 30000L));
        private boolean deferredUnloadQueued;
        private long deferredUnloadQueuedAtMs;
        private long deferredUnloadQueuedAtTick;
        private int deferredUnloadChunkX;
        private int deferredUnloadChunkY;

        public static boolean isDeferredUnloadEnabled() {
            return DEFERRED_UNLOAD_ENABLED && (DEFERRED_UNLOAD_ALLOW_COOP || CoopSlave.instance == null);
        }
        /**
         * Each submitted cell spends most of its RecalcAll2() time parked in
         * ApocBRMainThreadOrchestrator.submitAndWait() (native chunk registration, then
         * the erosion/MapObjects/Lua LoadGridsquare/LoadChunk hop chain), not doing CPU work.
         * A fixed-size platform-thread pool wastes real OS threads sitting in that park, which
         * caps how many cells can be mid-chain at once and starves the main thread's pump loop
         * between hops (see load2PumpIdleWait in telemetry). Virtual threads unmount while
         * parked in submitAndWait()'s future.join(), so the carrier is immediately free for
         * another cell's chain; this lets every cell in the current checkerboard color round
         * make progress concurrently instead of queueing behind a small fixed pool, without
         * needing any new locking (a color round is already guaranteed border-safe by
         * Load2Job's adjacency partitioning below).
         */
        private static final ExecutorService recalcPool = Executors.newVirtualThreadPerTaskExecutor();
        // ApocBR: both queues are now drained cooperatively with pumpFor() across ticks and from the
        // throttle-sleep idle window, never by parking in pumpUntil(), so they need the longer
        // task timeout - see ApocBRMainThreadOrchestrator.COOPERATIVE_TASK_TIMEOUT_NANOS.
        private static final ApocBRMainThreadOrchestrator load2MainThread = new ApocBRMainThreadOrchestrator(
            "load2MainPump",
            "load2MainTask",
            "load2PumpIdleWait",
            true
        );
        private static final ApocBRMainThreadOrchestrator load2MainThreadDeferred = new ApocBRMainThreadOrchestrator(
            "load2MainDeferredPump",
            "load2MainDeferredTask",
            "load2DeferredPumpIdleWait",
            true
        );
        static final int LOAD2_MAX_MS_PER_TICK = Math.max(1, Integer.getInteger("apocbr.load2.maxMsPerTick", 8));
        static final long LOAD2_MAX_NANOS_PER_TICK = LOAD2_MAX_MS_PER_TICK * 1000000L;
        static final boolean LOAD2_IDLE_ENABLED = !"false".equalsIgnoreCase(System.getProperty("apocbr.load2.idleEnabled", "true"));
        static final int LOAD2_IDLE_MAX_MS = Math.max(1, Integer.getInteger("apocbr.load2.idleMaxMs", 4));
        static final long LOAD2_IDLE_MAX_NANOS = LOAD2_IDLE_MAX_MS * 1000000L;
        /**
         * Liveness guard for a colour group that stops counting down entirely.
         *
         * The old pumpUntil() timeout served this purpose, but it conflated "a worker is wedged" with
         * "this is taking a while", so any slow group was destroyed. Now that the main thread never
         * parks on the latch, a slow group costs nothing and only a group that makes no progress at
         * all for this long is treated as broken.
         */
        static final long LOAD2_JOB_STALL_TIMEOUT_MS = Math.max(1000L, Long.getLong("apocbr.load2.jobStallTimeoutMs", 15000L));
        static ServerMap.ServerCell.Load2Job load2Job;
        /**
         * Load2 mutates shared, cross-cell world state: EnsureSurroundNotNull()/createNewGridSquare()
         * write directly into a neighbouring ServerCell's grid-square storage for cells across a border.
         * Running two adjacent cells concurrently would race on that storage.
         * To keep this safe while still using multiple cores, cells are bucketed into a 4-color
         * checkerboard by (wx & 1, wy & 1): within a color, no two cells are ever adjacent (even
         * diagonally), so their border writes can never collide. Colors are processed one at a time,
         * with a full barrier (CountDownLatch) between them. The main thread pumps Lua/main-affinity
         * handoffs while waiting for each color group to finish.
         */
        static final class Load2Job {
            private final ArrayList<ServerMap.ServerCell> cells;
            private final ArrayList<ArrayList<ServerMap.ServerCell>> colorGroups = new ArrayList<>(4);
            private int colorIndex = -1;
            private ArrayList<ServerMap.ServerCell> inFlight;
            private CountDownLatch latch;
            private long lastProgressMs = System.currentTimeMillis();
            private long lastLatchCount = Long.MAX_VALUE;
            private final long startedAtNanos = System.nanoTime();
            /** Counts advance() calls, which includes idle-window slices, not just ticks. */
            private int slices;

            Load2Job(ArrayList<ServerMap.ServerCell> src) {
                this.cells = new ArrayList<>(src);
                for (int i = 0; i < 4; i++) {
                    this.colorGroups.add(new ArrayList<>());
                }

                for (ServerMap.ServerCell cell : this.cells) {
                    this.colorGroups.get((cell.wx & 1) | ((cell.wy & 1) << 1)).add(cell);
                }
            }

            ArrayList<ServerMap.ServerCell> getCells() {
                return this.cells;
            }

            int getSlices() {
                return this.slices;
            }

            long getElapsedNanos() {
                return System.nanoTime() - this.startedAtNanos;
            }

            /**
             * Drains up to budgetNanos of worker handoffs and returns whether the whole job is done.
             *
             * The colour barrier is preserved across ticks: a colour is only dispatched once the
             * previous one has fully drained (latch clear AND queue empty), so two adjacent cells can
             * still never run concurrently, which is what makes EnsureSurroundNotNull()'s cross-border
             * writes safe. Several colours may complete in one call if the budget allows.
             */
            boolean advance(long budgetNanos) {
                long deadline = System.nanoTime() + budgetNanos;
                this.slices++;

                while (true) {
                    if (this.latch == null && !this.dispatchNextColor()) {
                        return true;
                    }

                    long remaining = Math.max(0L, deadline - System.nanoTime());
                    boolean colorDone = load2MainThread.pumpFor(this.latch, remaining);
                    load2MainThreadDeferred.drainAll();

                    if (!colorDone) {
                        this.checkStalled();
                        return false;
                    }

                    this.latch = null;
                    this.inFlight = null;
                    this.lastLatchCount = Long.MAX_VALUE;
                    this.lastProgressMs = System.currentTimeMillis();

                    if (System.nanoTime() >= deadline) {
                        return false;
                    }
                }
            }

            private boolean dispatchNextColor() {
                while (++this.colorIndex < 4) {
                    ArrayList<ServerMap.ServerCell> group = this.colorGroups.get(this.colorIndex);
                    if (group.isEmpty()) {
                        continue;
                    }

                    CountDownLatch groupLatch = new CountDownLatch(group.size());
                    for (ServerMap.ServerCell cell : group) {
                        cell.loadInProgress = true;
                    }

                    // Publish the group as in-flight BEFORE submitting. If execute() throws partway
                    // through (pool shutdown, rejection), the cells already submitted still count down
                    // and the rest never will - but with latch/inFlight set, checkStalled() sees a
                    // latch that stops moving and recovers them. Assigning after the loop instead
                    // would leave those cells pinned loadInProgress forever: never unloadable, and
                    // permanently skipped by ServerLOS.
                    this.latch = groupLatch;
                    this.inFlight = group;
                    this.lastLatchCount = group.size();
                    this.lastProgressMs = System.currentTimeMillis();

                    for (ServerMap.ServerCell cell : group) {
                        recalcPool.execute(() -> {
                            try {
                                long start = System.nanoTime();
                                cell.RecalcAll2();
                                long apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
                                ServerMap.runLoad2MainThreadTask("ServerCell.loadVehicles", cell::loadVehicles);
                                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2Vehicles", 1, apocBrPhaseStart);
                                if (ServerMap.mapLoading) {
                                    float time = (float)(System.nanoTime() - start) / 1000000.0F;
                                    DebugType.MapLoading.debugln("finish loading cell " + cell.wx + "," + cell.wy + " ms=" + time);
                                }
                            } catch (Throwable t) {
                                cell.cancelLoading = true;
                                cell.loadingWasCancelled = true;
                                cell.isLoaded = false;
                                ExceptionLogger.logException(t);
                            } finally {
                                cell.loadInProgress = false;
                                groupLatch.countDown();
                                load2MainThread.signalWorkAvailable();
                            }
                        });
                    }

                    return true;
                }

                return false;
            }

            /**
             * A budget expiry is normal and never cancels anything. Only a colour group that has not
             * counted down at all for LOAD2_JOB_STALL_TIMEOUT_MS is treated as broken - the case the
             * old pumpUntil() timeout existed for, where a worker dies or wedges and the latch would
             * otherwise never clear.
             */
            private void checkStalled() {
                long count = this.latch == null ? 0L : this.latch.getCount();
                long now = System.currentTimeMillis();
                if (count != this.lastLatchCount) {
                    this.lastLatchCount = count;
                    this.lastProgressMs = now;
                    return;
                }

                if (now - this.lastProgressMs < LOAD2_JOB_STALL_TIMEOUT_MS) {
                    return;
                }

                DebugLog.log(
                    "[ApocBR] load2 colour group "
                        + this.colorIndex
                        + " made no progress for "
                        + (now - this.lastProgressMs)
                        + " ms, latchRemaining="
                        + count
                        + "; cancelling "
                        + (this.inFlight == null ? 0 : this.inFlight.size())
                        + " cell(s)"
                );

                int apocBrCancelled = 0;
                if (this.inFlight != null) {
                    for (ServerMap.ServerCell cell : this.inFlight) {
                        cell.cancelLoading = true;
                        cell.loadingWasCancelled = true;
                        cell.isLoaded = false;
                        cell.loadInProgress = false;
                        apocBrCancelled++;
                    }
                }

                ApocBRServerTelemetry.recordServerMapPrePhase("load2StallCancel", apocBrCancelled, (now - this.lastProgressMs) * 1000000L);

                this.latch = null;
                this.inFlight = null;
                this.lastLatchCount = Long.MAX_VALUE;
                this.lastProgressMs = now;
            }
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

        private static void runLoad2ChunkRegistrationsOnMainThread(List<IsoChunk> chunks) {
            long apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            for (IsoChunk chunk : chunks) {
                long apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                MapCollisionData.instance.addChunkToWorld(chunk);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2NativeMapCollision", 1, apocBrDetailStart);

                apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                AnimalPopulationManager.getInstance().addChunkToWorld(chunk);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2NativeAnimalPop", 1, apocBrDetailStart);

                apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                ZombiePopulationManager.instance.addChunkToWorld(chunk);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2NativeZombiePop", 1, apocBrDetailStart);

                apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                if (PathfindNative.useNativeCode) {
                    PathfindNative.instance.addChunkToWorld(chunk);
                } else {
                    PolygonalMap2.instance.addChunkToWorld(chunk);
                }
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2NativePathfind", 1, apocBrDetailStart);

                apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                IsoGenerator.chunkLoaded(chunk);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2IsoGenerator", 1, apocBrDetailStart);

                apocBrDetailStart = ApocBRServerTelemetry.beginDetail();
                LootRespawn.chunkLoaded(chunk);
                ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2LootRespawn", 1, apocBrDetailStart);
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2NativeRegistrationBatch", chunks.size(), apocBrPhaseStart);
        }

        public void RecalcAll2() {
            int sx = this.wx * 8 * 8;
            int sy = this.wy * 8 * 8;
            int ex = sx + 64;
            int ey = sy + 64;

            long apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            for (RoomDef def : this.unexploredRooms) {
                def.indoorZombies--;
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2RoomsDec", this.unexploredRooms.size(), apocBrPhaseStart);

            this.unexploredRooms.clear();
            this.isLoaded = true;
            int minLevel = Integer.MAX_VALUE;
            int maxLevel = Integer.MIN_VALUE;

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            int apocBrUnits = 0;
            for (int chunkY = 0; chunkY < 8; chunkY++) {
                for (int chunkX = 0; chunkX < 8; chunkX++) {
                    IsoChunk chunk = this.getChunk(chunkX, chunkY);
                    if (chunk != null) {
                        apocBrUnits++;
                        minLevel = PZMath.min(minLevel, chunk.getMinLevel());
                        maxLevel = PZMath.max(maxLevel, chunk.getMaxLevel());
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2LevelScan", apocBrUnits, apocBrPhaseStart);

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
            for (int z = 1; z <= maxLevel; z++) {
                for (int x = -1; x < 65; x++) {
                    IsoGridSquare sq = ServerMap.instance.getGridSquare(sx + x, sy - 1, z);
                    if (sq != null && !sq.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                        apocBrUnits++;
                    } else if (x >= 0 && x < 64) {
                        sq = ServerMap.instance.getGridSquare(sx + x, sy, z);
                        if (sq != null && !sq.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                            apocBrUnits++;
                        }
                    }

                    sq = ServerMap.instance.getGridSquare(sx + x, sy + 64, z);
                    if (sq != null && !sq.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                        apocBrUnits++;
                    } else if (x >= 0 && x < 64) {
                        sq = ServerMap.instance.getGridSquare(sx + x, sy + 64 - 1, z);
                        if (sq != null && !sq.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, z);
                            apocBrUnits++;
                        }
                    }
                }

                for (int y = 0; y < 64; y++) {
                    IsoGridSquare sqx = ServerMap.instance.getGridSquare(sx - 1, sy + y, z);
                    if (sqx != null && !sqx.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                        apocBrUnits++;
                    } else {
                        sqx = ServerMap.instance.getGridSquare(sx, sy + y, z);
                        if (sqx != null && !sqx.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                            apocBrUnits++;
                        }
                    }

                    sqx = ServerMap.instance.getGridSquare(sx + 64, sy + y, z);
                    if (sqx != null && !sqx.getObjects().isEmpty()) {
                        IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                        apocBrUnits++;
                    } else {
                        sqx = ServerMap.instance.getGridSquare(sx + 64 - 1, sy + y, z);
                        if (sqx != null && !sqx.getObjects().isEmpty()) {
                            IsoWorld.instance.currentCell.EnsureSurroundNotNull(sqx.x, sqx.y, z);
                            apocBrUnits++;
                        }
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2EnsureSurround", apocBrUnits, apocBrPhaseStart);

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
            for (int z = minLevel; z <= maxLevel; z++) {
                for (int x = 0; x < 64; x++) {
                    IsoGridSquare sqxx = this.getGridSquareLocal(x, 0, z);
                    if (sqxx != null) {
                        sqxx.RecalcAllWithNeighbours(true);
                        apocBrUnits++;
                    }

                    sqxx = this.getGridSquareLocal(x, 63, z);
                    if (sqxx != null) {
                        sqxx.RecalcAllWithNeighbours(true);
                        apocBrUnits++;
                    }
                }

                for (int y = 1; y < 63; y++) {
                    IsoGridSquare sqxxx = this.getGridSquareLocal(0, y, z);
                    if (sqxxx != null) {
                        sqxxx.RecalcAllWithNeighbours(true);
                        apocBrUnits++;
                    }

                    sqxxx = this.getGridSquareLocal(63, y, z);
                    if (sqxxx != null) {
                        sqxxx.RecalcAllWithNeighbours(true);
                        apocBrUnits++;
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2BorderRecalc", apocBrUnits, apocBrPhaseStart);

            int nSquares = 64;

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
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
                                    apocBrUnits++;
                                }
                            }
                        }
                    }
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2MarkSquares", apocBrUnits, apocBrPhaseStart);

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
            ArrayList<IsoChunk> apocBrNativeRegistrationChunks = new ArrayList<>(64);
            ArrayList<IsoChunk> apocBrPostRegistrationChunks = new ArrayList<>(64);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoChunk chunk = this.chunks[x][y];
                    if (chunk != null) {
                        if (chunk.doLoadGridsquare(apocBrNativeRegistrationChunks)) {
                            apocBrPostRegistrationChunks.add(chunk);
                        }
                        apocBrUnits++;
                    }
                }
            }

            if (!apocBrNativeRegistrationChunks.isEmpty()) {
                ServerMap.runLoad2ChunkRegistrations(apocBrNativeRegistrationChunks);
            }

            // ApocBR: finishLoadGridsquareAfterChunkRegistration() is a chain of ~6 sequential
            // submitAndWait() hops to the main thread per chunk (erosion/MapObjects/Lua
            // LoadGridsquare, randomizeBuildingsEtc, checkAdjacentChunks, chunk-object-state flush,
            // Lua LoadChunk). Running that loop serially - one chunk's whole chain finishing before
            // the next chunk's first hop even starts - is what starves the main thread's pump loop
            // between hops (see load2PumpIdleWait in telemetry): with only one chunk in flight, there
            // are real gaps where nothing is queued. Fanning all of a cell's chunks out to their own
            // virtual thread on the same recalcPool means up to 64 chunks' hop chains are in flight
            // at once, so the main thread's queue - which is already being pumped by
            // Load2Job's color-round drain - stays continuously fed instead of idling
            // between each chunk. This is safe with respect to the checkerboard border-safety
            // invariant: it only changes how many chunks *within this one cell* run concurrently,
            // never across cells, and every method in the chain above that touches shared/adjacent
            // state (checkAdjacentChunks, the per-connection chunkObjectStateRequests flush,
            // frameDelay) has been made safe for concurrent chunk callers - see IsoChunk.java.
            if (!apocBrPostRegistrationChunks.isEmpty()) {
                CountDownLatch chunkFinishLatch = new CountDownLatch(apocBrPostRegistrationChunks.size());
                for (IsoChunk chunk : apocBrPostRegistrationChunks) {
                    recalcPool.execute(() -> {
                        try {
                            chunk.finishLoadGridsquareAfterChunkRegistration();
                        } catch (Throwable t) {
                            ExceptionLogger.logException(t);
                        } finally {
                            chunkFinishLatch.countDown();
                        }
                    });
                }

                try {
                    if (!chunkFinishLatch.await(LOAD2_CHUNK_FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        throw new RuntimeException(
                            "Timed out waiting for load2 chunk finish tasks in cell "
                                + this.wx
                                + ","
                                + this.wy
                                + " after "
                                + LOAD2_CHUNK_FINISH_TIMEOUT_MS
                                + " ms, remaining="
                                + chunkFinishLatch.getCount()
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2DoLoadGridSquare", apocBrUnits, apocBrPhaseStart);

            apocBrPhaseStart = ApocBRServerTelemetry.beginDetail();
            apocBrUnits = 0;
            for (RoomDef def : this.unexploredRooms) {
                def.indoorZombies++;
                // VirtualZombieManager.tryAddIndoorZombies(RoomDef, boolean) is an empty no-op method
                // in vanilla (zombie/VirtualZombieManager.java), even outside this patch. It has never
                // done anything, so the call is skipped here rather than paid for on every worker thread.
                apocBrUnits++;
            }
            ApocBRServerTelemetry.recordServerMapPrePhaseSince("load2RoomsInc", apocBrUnits, apocBrPhaseStart);

            this.isLoaded = true;
        }

        public boolean beginDeferredUnload(long currentUnloadTick) {
            // ApocBR: load2 now spans ticks, so a cell can be relevant-then-irrelevant while a worker
            // is still inside RecalcAll2(). Unloading underneath it would tear down squares the
            // worker is still writing. Leave it queued; it will be picked up once the load settles.
            if (!this.isLoaded || this.deferredUnloadQueued || this.loadInProgress) {
                return false;
            }

            if (ServerMap.mapLoading) {
                DebugType.MapLoading
                    .debugln(
                        "Queueing deferred unload cell: "
                            + this.wx
                            + ", "
                            + this.wy
                            + " ("
                            + ServerMap.instance.toWorldCellX(this.wx)
                            + ", "
                            + ServerMap.instance.toWorldCellY(this.wy)
                            + ")"
                    );
            }

            this.isLoaded = false;
            this.deferredUnloadQueued = true;
            this.deferredUnloadQueuedAtMs = System.currentTimeMillis();
            this.deferredUnloadQueuedAtTick = currentUnloadTick;
            this.deferredUnloadChunkX = 0;
            this.deferredUnloadChunkY = 0;
            return true;
        }

        public long getDeferredUnloadQueuedAtMs() {
            return this.deferredUnloadQueuedAtMs;
        }

        public long getDeferredUnloadAgeTicks(long currentUnloadTick) {
            return Math.max(0L, currentUnloadTick - this.deferredUnloadQueuedAtTick);
        }

        public boolean processDeferredUnloadSlice(int maxSquares) {
            if (!this.deferredUnloadQueued) {
                return true;
            }

            int squaresLeft = Math.max(64, maxSquares);

            while (this.deferredUnloadChunkX < 8) {
                IsoChunk chunk = this.chunks[this.deferredUnloadChunkX][this.deferredUnloadChunkY];
                if (chunk != null) {
                    if (!chunk.isRemoveFromWorldStarted()) {
                        long phaseStart = ApocBRServerTelemetry.beginDetail();
                        chunk.beginRemoveFromWorld();
                        ApocBRServerTelemetry.recordServerMapUnloadPhase("chunkGlobal", 1, System.nanoTime() - phaseStart);
                    }

                    long phaseStart = ApocBRServerTelemetry.beginDetail();
                    boolean finishedChunkSquares = chunk.processRemoveFromWorldSquares(squaresLeft);
                    ApocBRServerTelemetry.recordServerMapUnloadPhase("squareTeardown", 1, System.nanoTime() - phaseStart);
                    if (!finishedChunkSquares) {
                        return false;
                    }

                    squaresLeft -= Math.max(64, (chunk.maxLevel - chunk.minLevel + 1) * 64);

                    phaseStart = ApocBRServerTelemetry.beginDetail();
                    chunk.finishRemoveFromWorld();
                    chunk.loadVehiclesObject = null;

                    for (int i = 0; i < chunk.vehicles.size(); i++) {
                        BaseVehicle vehicle = chunk.vehicles.get(i);
                        VehiclesDB2.instance.updateVehicle(vehicle);
                    }
                    ApocBRServerTelemetry.recordServerMapUnloadPhase("vehicleSave", chunk.vehicles.size(), System.nanoTime() - phaseStart);

                    phaseStart = ApocBRServerTelemetry.beginDetail();
                    chunkLoader.addSaveUnloadedJob(chunk);
                    this.chunks[this.deferredUnloadChunkX][this.deferredUnloadChunkY] = null;
                    ApocBRServerTelemetry.recordServerMapUnloadPhase("saveEnqueue", 1, System.nanoTime() - phaseStart);
                }

                this.deferredUnloadChunkY++;
                if (this.deferredUnloadChunkY >= 8) {
                    this.deferredUnloadChunkY = 0;
                    this.deferredUnloadChunkX++;
                }

                if (squaresLeft <= 0 && this.deferredUnloadChunkX < 8) {
                    return false;
                }
            }

            for (RoomDef def : this.unexploredRooms) {
                def.indoorZombies--;
            }

            this.unexploredRooms.clear();
            this.deferredUnloadQueued = false;
            this.deferredUnloadQueuedAtMs = 0L;
            this.deferredUnloadQueuedAtTick = 0L;
            this.deferredUnloadChunkX = 0;
            this.deferredUnloadChunkY = 0;
            return true;
        }

        public void Unload() {
            if (this.isLoaded) {
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
                                + ")"
                        );
                }

                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        IsoChunk chunk = this.chunks[x][y];
                        if (chunk != null) {
                            chunk.removeFromWorld();
                            chunk.loadVehiclesObject = null;

                            for (int i = 0; i < chunk.vehicles.size(); i++) {
                                BaseVehicle vehicle = chunk.vehicles.get(i);
                                VehiclesDB2.instance.updateVehicle(vehicle);
                            }

                            chunkLoader.addSaveUnloadedJob(chunk);
                            this.chunks[x][y] = null;
                        }
                    }
                }

                for (RoomDef def : this.unexploredRooms) {
                    def.indoorZombies--;
                }
            }
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

        private IsoGridSquare getGridSquareLocal(int localX, int localY, int z) {
            if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64) {
                return null;
            }

            IsoChunk chunk = this.chunks[localX / 8][localY / 8];
            return chunk == null ? null : chunk.getGridSquare(localX % 8, localY % 8, z);
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
