// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.ApocBRServerTelemetry;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.VisibilityData;
import zombie.core.math.PZMath;
import zombie.core.textures.ColorInfo;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;

public class ServerLOS {
    public static ServerLOS instance;
    private ServerLOS.LOSDispatcher thread;
    private final ArrayList<ServerLOS.PlayerData> playersMain = new ArrayList<>();
    // ApocBR: findData() below used to scan playersMain linearly. isCouldSee() is called
    // per-zombie per-tick (IsoZombie.isTargetVisible(), IsoGameCharacter visibility checks),
    // so that scan was O(zombies * players) just for the lookup. IsoPlayer has no
    // equals()/hashCode() override, so an IdentityHashMap keyed by player gives the same
    // identity-based lookup in O(1).
    private final IdentityHashMap<IsoPlayer, ServerLOS.PlayerData> playersMainByPlayer = new IdentityHashMap<>();
    private volatile boolean mapLoading;
    private volatile boolean suspended;
    private static final int PD_SIZE_IN_CHUNKS = 12;
    private static final int PD_SIZE_IN_SQUARES = 96;
    boolean wasSuspended;

    // ApocBR: IsoGridSquare.lighting[] is kept aligned with LosUtil.SLOT_COUNT.
    private static final int LOS_SLOT_COUNT = LosUtil.SLOT_COUNT;
    private static final int LOS_WORKER_THREADS = 6;
    private final BlockingQueue<Integer> freeSlots = new ArrayBlockingQueue<>(LOS_SLOT_COUNT);
    private final BlockingQueue<PlayerData> losQueue = new LinkedBlockingQueue<>();
    private final ExecutorService losPool = Executors.newFixedThreadPool(
        LOS_WORKER_THREADS,
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ServerLOS-worker-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        }
    );
    private static final int LOS_THROTTLE_PHASES = 10;
    private static final int LOS_THROTTLE_RUN_PHASE = 0;
    private static final int LOS_THROTTLE_MAX_DEFER_ROUNDS = 10;
    private int losThrottleRound;
    private long losThrottleFrame = -1L;

    private void noise(String str) {
    }

    public static void init() {
        instance = new ServerLOS();
        instance.start();
    }

    public void start() {
        for (int i = 0; i < LOS_SLOT_COUNT; i++) {
            this.freeSlots.add(i);
        }

        ApocBRServerTelemetry.recordServerLosSlotCount(LOS_SLOT_COUNT);
        ((java.util.concurrent.ThreadPoolExecutor)this.losPool).prestartAllCoreThreads();
        this.thread = new ServerLOS.LOSDispatcher();
        this.thread.setName("LOS-listener");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void addPlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            if (this.findData(player) == null) {
                ServerLOS.PlayerData data = new ServerLOS.PlayerData(player);
                this.playersMain.add(data);
                this.playersMainByPlayer.put(player, data);
            }
        }
    }

    public void removePlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            ServerLOS.PlayerData data = this.findData(player);
            this.playersMain.remove(data);
            this.playersMainByPlayer.remove(player);
            this.losQueue.remove(data);
        }
    }

    public boolean isCouldSee(IsoPlayer player, IsoGridSquare sq) {
        ServerLOS.PlayerData data = this.findData(player);
        if (data != null) {
            int minX = data.px - 48;
            int minY = data.py - 48;
            int minZ = data.pz - LosUtil.sizeZ / 2;
            int x = sq.x - minX;
            int y = sq.y - minY;
            int z = sq.z - minZ;
            if (x >= 0 && x < 96 && y >= 0 && y < 96 && z >= 0 && z < LosUtil.sizeZ) {
                return data.visible[x][y][z];
            }
        }

        return false;
    }

    public void doServerZombieLOS(IsoPlayer player) {
        if (ServerMap.instance.updateLosThisFrame) {
            this.updateThrottleRound();
            ServerLOS.PlayerData data = this.findData(player);
            if (data != null) {
                boolean forceSchedule = data.status == ServerLOS.UpdateStatus.NeverDone || this.isLosThrottleExpired(data);
                if (data.status == ServerLOS.UpdateStatus.NeverDone) {
                    data.status = ServerLOS.UpdateStatus.ReadyInMain;
                }

                if (data.status == ServerLOS.UpdateStatus.ReadyInMain) {
                    if (!forceSchedule && this.shouldThrottleLos(player)) {
                        ApocBRServerTelemetry.recordServerLosPhased();
                    } else {
                        if (forceSchedule) {
                            ApocBRServerTelemetry.recordServerLosForced();
                        }

                        data.lastQueuedLosRound = this.losThrottleRound;
                        data.status = ServerLOS.UpdateStatus.WaitingInLOS;
                        this.losQueue.offer(data);
                        this.noise("WaitingInLOS playerID=" + player.onlineId);
                        synchronized (this.thread.notifier) {
                            this.thread.notifier.notify();
                        }
                    }
                }
            }
        }
    }

    private void updateThrottleRound() {
        long frame = IsoCamera.frameState.frameCount;
        if (frame != this.losThrottleFrame) {
            this.losThrottleFrame = frame;
            this.losThrottleRound++;
        }
    }

    private boolean shouldThrottleLos(IsoPlayer player) {
        return Math.floorMod(this.losThrottleRound + player.onlineId, LOS_THROTTLE_PHASES) != LOS_THROTTLE_RUN_PHASE;
    }

    private boolean isLosThrottleExpired(ServerLOS.PlayerData data) {
        return data.lastQueuedLosRound == Integer.MIN_VALUE
            || this.losThrottleRound - data.lastQueuedLosRound >= LOS_THROTTLE_MAX_DEFER_ROUNDS;
    }

    public void updateLOS(IsoPlayer player) {
        ServerLOS.PlayerData data = this.findData(player);
        if (data != null) {
            if (data.status == ServerLOS.UpdateStatus.ReadyInLOS || data.status == ServerLOS.UpdateStatus.ReadyInMain) {
                if (data.status == ServerLOS.UpdateStatus.ReadyInLOS) {
                    this.noise("BusyInMain playerID=" + player.onlineId);
                }

                data.status = ServerLOS.UpdateStatus.BusyInMain;
                player.updateLOS();
                data.status = ServerLOS.UpdateStatus.ReadyInMain;
                synchronized (this.thread.notifier) {
                    this.thread.notifier.notify();
                }
            }
        }
    }

    private ServerLOS.PlayerData findData(IsoPlayer player) {
        return this.playersMainByPlayer.get(player);
    }

    public void suspend() {
        this.mapLoading = true;
        this.wasSuspended = this.suspended;
        this.losQueue.clear();
        synchronized (this.thread.notifier) {
            this.thread.notifier.notify();
        }

        while (this.freeSlots.size() < LOS_SLOT_COUNT) {
            try {
                Thread.sleep(1L);
            } catch (InterruptedException var2) {
            }
        }

        this.suspended = true;
        if (!this.wasSuspended) {
            this.noise("suspend **********");
        }
    }

    public void resume() {
        this.mapLoading = false;
        this.suspended = false;
        synchronized (this.thread.notifier) {
            this.thread.notifier.notify();
        }

        if (!this.wasSuspended) {
            this.noise("resume **********");
        }
    }

    // ApocBR: calcLOS is now a plain instance method (not nested in the dispatcher thread)
    // so it can be invoked from losPool worker threads,
    // bound to whichever lighting[]/cachedresults[] slot index the dispatcher handed out.
    private boolean calcLOS(ServerLOS.PlayerData data, int slotIndex) {
        boolean skip = data.px == PZMath.fastfloor(data.player.getX())
            && data.py == PZMath.fastfloor(data.player.getY())
            && data.pz == PZMath.fastfloor(data.player.getZ());
        data.px = PZMath.fastfloor(data.player.getX());
        data.py = PZMath.fastfloor(data.player.getY());
        data.pz = PZMath.fastfloor(data.player.getZ());
        data.player.initLightInfo2();
        if (!skip) {
            LosUtil.PerPlayerData ppd = LosUtil.cachedresults[slotIndex];
            ppd.checkSize();

            // ApocBR: cachedresults is sized sizeX x sizeY x sizeZ (200x200x16 by default) to
            // cover the whole map, but a single calc only ever reads/writes the
            // PD_SIZE_IN_SQUARES (96) window centered on the player - lineClearCached's cache
            // index is (targetCoord - playerCoord) + size/2, and every target square scanned
            // below is within +-48 of the player in X/Y (Z already matches sizeZ exactly). The
            // old loop zeroed all 200x200x16 cells every real calc, ~4.3x more than the window
            // that is actually touched. Clipping the zero-fill to that same window is a pure
            // perf fix with no behavior change, since cells outside it are never read this pass.
            int zeroMinX = LosUtil.sizeX / 2 - PD_SIZE_IN_SQUARES / 2;
            int zeroMaxX = zeroMinX + PD_SIZE_IN_SQUARES;
            int zeroMinY = LosUtil.sizeY / 2 - PD_SIZE_IN_SQUARES / 2;
            int zeroMaxY = zeroMinY + PD_SIZE_IN_SQUARES;

            for (int x = zeroMinX; x < zeroMaxX; x++) {
                for (int y = zeroMinY; y < zeroMaxY; y++) {
                    for (int z = 0; z < LosUtil.sizeZ; z++) {
                        ppd.cachedresults[x][y][z] = 0;
                    }
                }
            }

            int playerX = data.px;
            int playerY = data.py;
            int playerZ = data.pz;
            int minX = playerX - 48;
            int maxX = minX + 96;
            int minY = playerY - 48;
            int maxY = minY + 96;
            int minZ = playerZ - LosUtil.sizeZ / 2;
            int maxZ = minZ + LosUtil.sizeZ;
            IsoGameCharacter isoGameCharacter = data.player;
            VisibilityData visibilityData = isoGameCharacter.calculateVisibilityData();

            for (int x = minX; x < maxX; x++) {
                for (int y = minY; y < maxY; y++) {
                    // ApocBR: ServerMap.getGridSquare(x, y, z) re-resolves the owning
                    // cell/chunk (coordinate division/modulo, cell lookup, isLoaded check)
                    // from scratch for every z, even though none of that depends on z - only
                    // the final chunk.getGridSquare(sqx, sqy, z) call does. Resolving the
                    // chunk once per (x, y) column and reusing it across the z loop below
                    // cuts that redundant resolution ~16x (LosUtil.sizeZ per column) with no
                    // behavior change. z=0 is always within the valid range (-32..31), so
                    // isValidSquare(x, y, 0) reads exactly the z-independent metaGrid check.
                    int rawCx = ServerMap.instance.worldSquareToServerCellXY(x);
                    int rawCy = ServerMap.instance.worldSquareToServerCellXY(y);
                    int chx = (x - rawCx * 64) / 8;
                    int chy = (y - rawCy * 64) / 8;
                    int sqx = (x - rawCx * 64) % 8;
                    int sqy = (y - rawCy * 64) % 8;
                    IsoChunk chunk = null;
                    if (IsoWorld.instance.isValidSquare(x, y, 0)) {
                        int cellX = rawCx - ServerMap.instance.getMinX();
                        int cellY = rawCy - ServerMap.instance.getMinY();
                        ServerMap.ServerCell cell = ServerMap.instance.getCell(cellX, cellY);
                        chunk = cell != null && cell.isLoaded ? cell.chunks[chx][chy] : null;
                    }

                    for (int z = minZ; z < maxZ; z++) {
                        IsoGridSquare sq = chunk != null && z >= -32 && z <= 31 ? chunk.getGridSquare(sqx, sqy, z) : null;
                        if (sq != null) {
                            sq.CalcVisibility(slotIndex, isoGameCharacter, visibilityData);
                            data.visible[x - minX][y - minY][z - minZ] = sq.isCouldSee(slotIndex);
                            sq.checkRoomSeen(data.player);
                        } else {
                            data.visible[x - minX][y - minY][z - minZ] = false;
                        }
                    }
                }
            }
        }

        return skip;
    }

    private void process(PlayerData data) {
        if (ServerLOS.this.mapLoading) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            return;
        }

        int slot;
        try {
            slot = this.freeSlots.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            return;
        }

        boolean skipped = true;
        long startNanos = System.nanoTime();
        try {
            data.status = ServerLOS.UpdateStatus.BusyInLOS;
            this.noise("BusyInLOS playerID=" + data.player.onlineId);
            skipped = this.calcLOS(data, slot);
        } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
        } finally {
            ApocBRServerTelemetry.recordServerLosCalc(skipped, System.nanoTime() - startNanos);
            data.status = ServerLOS.UpdateStatus.ReadyInLOS;
            this.freeSlots.offer(slot);
        }
    }

    private class LOSDispatcher extends Thread {
        public final Object notifier;

        private LOSDispatcher() {
            Objects.requireNonNull(ServerLOS.this);
            super();
            this.notifier = new Object();
        }

        @Override
        public void run() {
            while (true) {
                try {
                    synchronized (this.notifier) {
                        while (ServerLOS.this.mapLoading) {
                            this.notifier.wait();
                        }
                    }

                    ServerLOS.PlayerData data = ServerLOS.this.losQueue.take();
                    ApocBRServerTelemetry.recordServerLosDispatch();
                    ServerLOS.this.losPool.execute(() -> ServerLOS.this.process(data));
                } catch (Exception ex) {
                    DebugType.General.printException(ex, LogSeverity.Error);
                }
            }
        }
    }

    private static final class PlayerData {
        public IsoPlayer player;
        // ApocBR: status is now read/written across the main thread, the LOS dispatcher
        // thread, and losPool worker threads (see calcLOS/process), so it needs to be
        // volatile for cross-thread visibility instead of a plain field.
        public volatile ServerLOS.UpdateStatus status = ServerLOS.UpdateStatus.NeverDone;
        public int px;
        public int py;
        public int pz;
        public int lastQueuedLosRound = Integer.MIN_VALUE;
        public boolean[][][] visible = new boolean[96][96][LosUtil.sizeZ];

        public PlayerData(IsoPlayer player) {
            this.player = player;
        }
    }

    public static final class ServerLighting implements IsoGridSquare.ILighting {
        private static final byte LOS_SEEN = 1;
        private static final byte LOS_COULD_SEE = 2;
        private static final byte LOS_CAN_SEE = 4;
        private static final ColorInfo lightInfo = new ColorInfo();
        private byte los;

        @Override
        public int lightverts(int i) {
            return 0;
        }

        @Override
        public float lampostTotalR() {
            return 0.0F;
        }

        @Override
        public float lampostTotalG() {
            return 0.0F;
        }

        @Override
        public float lampostTotalB() {
            return 0.0F;
        }

        @Override
        public boolean bSeen() {
            return (this.los & 1) != 0;
        }

        @Override
        public boolean bCanSee() {
            return (this.los & 4) != 0;
        }

        @Override
        public boolean bCouldSee() {
            return (this.los & 2) != 0;
        }

        @Override
        public float darkMulti() {
            return 0.0F;
        }

        @Override
        public float targetDarkMulti() {
            return 0.0F;
        }

        @Override
        public ColorInfo lightInfo() {
            lightInfo.r = 1.0F;
            lightInfo.g = 1.0F;
            lightInfo.b = 1.0F;
            return lightInfo;
        }

        @Override
        public void lightverts(int i, int value) {
        }

        @Override
        public void lampostTotalR(float r) {
        }

        @Override
        public void lampostTotalG(float g) {
        }

        @Override
        public void lampostTotalB(float b) {
        }

        @Override
        public void bSeen(boolean seen) {
            if (seen) {
                this.los = (byte)(this.los | 1);
            } else {
                this.los &= -2;
            }
        }

        @Override
        public void bCanSee(boolean canSee) {
            if (canSee) {
                this.los = (byte)(this.los | 4);
            } else {
                this.los &= -5;
            }
        }

        @Override
        public void bCouldSee(boolean couldSee) {
            if (couldSee) {
                this.los = (byte)(this.los | 2);
            } else {
                this.los &= -3;
            }
        }

        @Override
        public void darkMulti(float f) {
        }

        @Override
        public void targetDarkMulti(float f) {
        }

        @Override
        public int resultLightCount() {
            return 0;
        }

        @Override
        public IsoGridSquare.ResultLight getResultLight(int index) {
            return null;
        }

        @Override
        public void reset() {
            this.los = 0;
        }
    }

    static enum UpdateStatus {
        NeverDone,
        WaitingInLOS,
        BusyInLOS,
        ReadyInLOS,
        BusyInMain,
        ReadyInMain;
    }
}
