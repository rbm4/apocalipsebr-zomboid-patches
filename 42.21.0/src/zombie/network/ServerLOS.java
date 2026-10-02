// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import zombie.ApocBRServerTelemetryLite;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.VisibilityData;
import zombie.core.math.PZMath;
import zombie.core.textures.ColorInfo;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.LosUtil;
import zombie.meta.Meta;

public class ServerLOS {
    public static ServerLOS instance;
    private static final int PD_SIZE_IN_SQUARES = 96;
    private static final int LOS_SLOT_COUNT = LosUtil.SLOT_COUNT;
    private static final int LOS_WORKER_THREADS = PZMath.clamp(Integer.getInteger("apocbr.los.workerThreads", LOS_SLOT_COUNT), 1, LOS_SLOT_COUNT);
    private static final int LOS_QUEUE_CAPACITY = Math.max(16, Integer.getInteger("apocbr.los.queueCapacity", 256));
    private static final long LOS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(Math.max(250L, Long.getLong("apocbr.los.intervalMs", 2000L)));
    private static final float MAX_RESULT_MOVEMENT_SQUARED = 4.0F;
    private final ArrayList<ServerLOS.PlayerData> playersMain = new ArrayList<>();
    private final IdentityHashMap<IsoPlayer, ServerLOS.PlayerData> playersByPlayer = new IdentityHashMap<>();
    private final BlockingQueue<Integer> freeSlots = new ArrayBlockingQueue<>(LOS_SLOT_COUNT);
    private final AtomicLong worldGeneration = new AtomicLong();
    private final ThreadPoolExecutor losPool = new ThreadPoolExecutor(
        LOS_WORKER_THREADS,
        LOS_WORKER_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(LOS_QUEUE_CAPACITY),
        new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ServerLOS-worker-" + this.counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        },
        new ThreadPoolExecutor.AbortPolicy()
    );
    private volatile boolean mapLoading;
    private volatile boolean suspended;
    boolean wasSuspended;

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

        this.losPool.prestartAllCoreThreads();
        ApocBRServerTelemetryLite.start();
    }

    public void addPlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            if (this.findData(player) == null) {
                ServerLOS.PlayerData data = new ServerLOS.PlayerData(player);
                this.playersMain.add(data);
                this.playersByPlayer.put(player, data);
            }
        }
    }

    public void removePlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            ServerLOS.PlayerData data = this.findData(player);
            if (data != null) {
                data.removed = true;
                this.playersMain.remove(data);
                this.playersByPlayer.remove(player);
            }
        }
    }

    public boolean isCouldSee(IsoPlayer player, IsoGridSquare square) {
        ServerLOS.PlayerData data = this.findData(player);
        if (data != null && data.hasResult) {
            int minX = data.px - 48;
            int minY = data.py - 48;
            int minZ = data.pz - LosUtil.sizeZ / 2;
            int x = square.x - minX;
            int y = square.y - minY;
            int z = square.z - minZ;
            if (x >= 0 && x < PD_SIZE_IN_SQUARES && y >= 0 && y < PD_SIZE_IN_SQUARES && z >= 0 && z < LosUtil.sizeZ) {
                return data.visible[x][y][z];
            }
        }

        return false;
    }

    public void doServerZombieLOS(IsoPlayer player) {
        if (this.mapLoading) {
            return;
        }

        ServerLOS.PlayerData data = this.findData(player);
        if (data == null || data.removed || data.status == ServerLOS.UpdateStatus.BusyInLOS || data.status == ServerLOS.UpdateStatus.WaitingInLOS) {
            return;
        }

        long now = System.nanoTime();
        if (data.status == ServerLOS.UpdateStatus.NeverDone) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = 0L;
        }

        if (data.status != ServerLOS.UpdateStatus.ReadyInMain || now < data.nextScheduleNanos) {
            return;
        }

        data.requestX = PZMath.fastfloor(player.getX());
        data.requestY = PZMath.fastfloor(player.getY());
        data.requestZ = PZMath.fastfloor(player.getZ());
        data.requestGeneration = this.worldGeneration.get();
        player.initLightInfo2();
        data.visibilityData = player.calculateVisibilityData();
        data.status = ServerLOS.UpdateStatus.WaitingInLOS;
        try {
            this.losPool.execute(() -> this.process(data));
        } catch (RuntimeException exception) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = now + LOS_INTERVAL_NANOS;
            DebugType.General.printException(exception, LogSeverity.Error);
        }
    }

    public void updateLOS(IsoPlayer player) {
        ServerLOS.PlayerData data = this.findData(player);
        if (data == null || data.status != ServerLOS.UpdateStatus.ReadyInLOS) {
            return;
        }

        if (!this.isResultCurrent(data)) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = 0L;
            return;
        }

        data.status = ServerLOS.UpdateStatus.BusyInMain;
        try {
            for (int i = 0; i < data.roomSeenCount; i++) {
                IsoGridSquare square = ServerMap.instance.getGridSquare(data.roomSeenX[i], data.roomSeenY[i], data.roomSeenZ[i]);
                if (square != null) {
                    square.checkRoomSeen(player);
                    Meta.instance.dealWithSquareSeen(square);
                }
            }

            player.updateLOS();
        } finally {
            data.roomSeenCount = 0;
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = System.nanoTime() + LOS_INTERVAL_NANOS;
        }
    }

    private boolean isResultCurrent(ServerLOS.PlayerData data) {
        if (data.resultGeneration != this.worldGeneration.get()) {
            return false;
        }

        IsoPlayer player = data.player;
        if (PZMath.fastfloor(player.getZ()) != data.pz) {
            return false;
        }

        float dx = player.getX() - data.px;
        float dy = player.getY() - data.py;
        return dx * dx + dy * dy <= MAX_RESULT_MOVEMENT_SQUARED;
    }

    private ServerLOS.PlayerData findData(IsoPlayer player) {
        synchronized (this.playersMain) {
            return this.playersByPlayer.get(player);
        }
    }

    public void suspend() {
        this.mapLoading = true;
        this.worldGeneration.incrementAndGet();
        this.wasSuspended = this.suspended;
        this.losPool.getQueue().clear();
        synchronized (this.playersMain) {
            for (ServerLOS.PlayerData data : this.playersMain) {
                if (data.status == ServerLOS.UpdateStatus.WaitingInLOS || data.status == ServerLOS.UpdateStatus.ReadyInLOS) {
                    data.status = ServerLOS.UpdateStatus.ReadyInMain;
                    data.nextScheduleNanos = 0L;
                }
            }
        }

        try (ApocBRServerTelemetryLite.Scope telemetry = ApocBRServerTelemetryLite.phase("map.losSuspendWait")) {
            while (this.freeSlots.size() < LOS_SLOT_COUNT) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
        if (!this.wasSuspended) {
            this.noise("resume **********");
        }
    }

    private void process(ServerLOS.PlayerData data) {
        if (this.mapLoading || data.removed || data.requestGeneration != this.worldGeneration.get()) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = 0L;
            return;
        }

        int slot;
        try {
            slot = this.freeSlots.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = 0L;
            return;
        }

        try {
            data.status = ServerLOS.UpdateStatus.BusyInLOS;
            this.calcLOS(data, slot);
            if (!this.mapLoading && !data.removed && data.requestGeneration == this.worldGeneration.get()) {
                boolean[][][] previousVisible = data.visible;
                data.visible = data.workingVisible;
                data.workingVisible = previousVisible;
                data.resultGeneration = data.requestGeneration;
                data.hasResult = true;
                data.status = ServerLOS.UpdateStatus.ReadyInLOS;
            } else {
                data.status = ServerLOS.UpdateStatus.ReadyInMain;
                data.nextScheduleNanos = 0L;
            }
        } catch (Exception exception) {
            data.status = ServerLOS.UpdateStatus.ReadyInMain;
            data.nextScheduleNanos = 0L;
            DebugType.General.printException(exception, LogSeverity.Error);
        } finally {
            this.freeSlots.offer(slot);
        }
    }

    private void calcLOS(ServerLOS.PlayerData data, int slotIndex) {
        LosUtil.PerPlayerData perPlayerData = LosUtil.cachedresults[slotIndex];
        perPlayerData.checkSize();
        int zeroMinX = LosUtil.sizeX / 2 - PD_SIZE_IN_SQUARES / 2;
        int zeroMaxX = zeroMinX + PD_SIZE_IN_SQUARES;
        int zeroMinY = LosUtil.sizeY / 2 - PD_SIZE_IN_SQUARES / 2;
        int zeroMaxY = zeroMinY + PD_SIZE_IN_SQUARES;
        for (int x = zeroMinX; x < zeroMaxX; x++) {
            for (int y = zeroMinY; y < zeroMaxY; y++) {
                for (int z = 0; z < LosUtil.sizeZ; z++) {
                    perPlayerData.cachedresults[x][y][z] = 0;
                }
            }
        }

        int playerX = data.requestX;
        int playerY = data.requestY;
        int playerZ = data.requestZ;
        int minX = playerX - 48;
        int maxX = minX + PD_SIZE_IN_SQUARES;
        int minY = playerY - 48;
        int maxY = minY + PD_SIZE_IN_SQUARES;
        int minZ = playerZ - LosUtil.sizeZ / 2;
        int maxZ = minZ + LosUtil.sizeZ;
        IsoGameCharacter character = data.player;
        VisibilityData visibilityData = data.visibilityData;
        data.roomSeenCount = 0;
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    IsoGridSquare square = ServerMap.instance.getGridSquare(x, y, z);
                    boolean couldSee = false;
                    if (square != null) {
                        square.CalcVisibility(slotIndex, character, visibilityData);
                        couldSee = square.isCouldSee(slotIndex);
                        if (couldSee && square.getRoom() != null) {
                            data.addRoomSeen(x, y, z);
                        }
                    }

                    data.workingVisible[x - minX][y - minY][z - minZ] = couldSee;
                }
            }
        }

        data.px = playerX;
        data.py = playerY;
        data.pz = playerZ;
    }

    private static final class PlayerData {
        final IsoPlayer player;
        volatile boolean[][][] visible = new boolean[PD_SIZE_IN_SQUARES][PD_SIZE_IN_SQUARES][LosUtil.sizeZ];
        boolean[][][] workingVisible = new boolean[PD_SIZE_IN_SQUARES][PD_SIZE_IN_SQUARES][LosUtil.sizeZ];
        volatile ServerLOS.UpdateStatus status = ServerLOS.UpdateStatus.NeverDone;
        volatile boolean removed;
        boolean hasResult;
        int px;
        int py;
        int pz;
        int requestX;
        int requestY;
        int requestZ;
        long requestGeneration;
        long resultGeneration;
        long nextScheduleNanos;
        VisibilityData visibilityData;
        int[] roomSeenX = new int[256];
        int[] roomSeenY = new int[256];
        int[] roomSeenZ = new int[256];
        int roomSeenCount;

        PlayerData(IsoPlayer player) {
            this.player = player;
        }

        void addRoomSeen(int x, int y, int z) {
            if (this.roomSeenCount == this.roomSeenX.length) {
                int newLength = this.roomSeenCount * 2;
                this.roomSeenX = java.util.Arrays.copyOf(this.roomSeenX, newLength);
                this.roomSeenY = java.util.Arrays.copyOf(this.roomSeenY, newLength);
                this.roomSeenZ = java.util.Arrays.copyOf(this.roomSeenZ, newLength);
            }

            this.roomSeenX[this.roomSeenCount] = x;
            this.roomSeenY[this.roomSeenCount] = y;
            this.roomSeenZ[this.roomSeenCount] = z;
            this.roomSeenCount++;
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
            return (this.los & LOS_SEEN) != 0;
        }

        @Override
        public boolean bCanSee() {
            return (this.los & LOS_CAN_SEE) != 0;
        }

        @Override
        public boolean bCouldSee() {
            return (this.los & LOS_COULD_SEE) != 0;
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
                this.los = (byte)(this.los | LOS_SEEN);
            } else {
                this.los = (byte)(this.los & ~LOS_SEEN);
            }
        }

        @Override
        public void bCanSee(boolean canSee) {
            if (canSee) {
                this.los = (byte)(this.los | LOS_CAN_SEE);
            } else {
                this.los = (byte)(this.los & ~LOS_CAN_SEE);
            }
        }

        @Override
        public void bCouldSee(boolean couldSee) {
            if (couldSee) {
                this.los = (byte)(this.los | LOS_COULD_SEE);
            } else {
                this.los = (byte)(this.los & ~LOS_COULD_SEE);
            }
        }

        @Override
        public void darkMulti(float value) {
        }

        @Override
        public void targetDarkMulti(float value) {
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
