// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.VisibilityData;
import zombie.core.math.PZMath;
import zombie.core.textures.ColorInfo;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoCell;
import zombie.iso.LosUtil;
import zombie.iso.IsoMovingObject;

public class ServerLOS {
    public static ServerLOS instance;
    private ServerLOS.LOSThread thread;
    private final ArrayList<ServerLOS.PlayerData> playersMain = new ArrayList<>();
    private final ArrayList<ServerLOS.PlayerData> playersLos = new ArrayList<>();
    private volatile boolean mapLoading;
    private volatile boolean suspended;
    private static final long SUSPEND_WAIT_TIMEOUT_MS = 500L;
    private static final int PD_SIZE_IN_CHUNKS = 12;
    private static final int PD_SIZE_IN_SQUARES = 96;
    // Object-level LOS is a player × candidate cost. The tile visibility grid
    // retains its vanilla cadence; only this higher-level object scan is capped.
    private static final long OBJECT_LOS_INTERVAL_BASE_NANOS = Math.max(50L,
            Math.min(1000L, Long.getLong("apocbr.los.objectIntervalMs", 200L))) * 1_000_000L;
    private static final long OBJECT_LOS_INTERVAL_MAX_NANOS = Math.max(OBJECT_LOS_INTERVAL_BASE_NANOS,
            Math.min(2000L, Long.getLong("apocbr.los.objectIntervalMaxMs", 750L))) * 1_000_000L;
    private static final long CANDIDATE_INDEX_INTERVAL_BASE_NANOS = 100_000_000L;
    private static final long CANDIDATE_INDEX_INTERVAL_MAX_NANOS = 400_000_000L;
    private static final int CANDIDATE_RADIUS_CHUNKS = 7;
    private final HashMap<Long, ArrayList<IsoMovingObject>> objectCandidatesByChunk = new HashMap<>();
    private IsoCell candidateIndexCell;
    private long candidateIndexBuiltNanos;
    volatile boolean wasSuspended;

    private void noise(String str) {
    }

    public static void init() {
        instance = new ServerLOS();
        instance.start();
    }

    public void start() {
        this.thread = new ServerLOS.LOSThread();
        this.thread.setName("LOS");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public void addPlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            if (this.findData(player) == null) {
                ServerLOS.PlayerData data = new ServerLOS.PlayerData(player);
                this.playersMain.add(data);
                synchronized (this.thread.notifier) {
                    this.thread.notifier.notify();
                }
            }
        }
    }

    public void removePlayer(IsoPlayer player) {
        synchronized (this.playersMain) {
            ServerLOS.PlayerData data = this.findData(player);
            this.playersMain.remove(data);
            synchronized (this.thread.notifier) {
                this.thread.notifier.notify();
            }
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
            ServerLOS.PlayerData data = this.findData(player);
            if (data != null) {
                if (data.status == ServerLOS.UpdateStatus.NeverDone) {
                    data.status = ServerLOS.UpdateStatus.ReadyInMain;
                }

                if (data.status == ServerLOS.UpdateStatus.ReadyInMain) {
                    data.status = ServerLOS.UpdateStatus.WaitingInLOS;
                    this.noise("WaitingInLOS playerID=" + player.onlineId);
                    synchronized (this.thread.notifier) {
                        this.thread.notifier.notify();
                    }
                }
            }
        }
    }

    public void updateLOS(IsoPlayer player) {
        ServerLOS.PlayerData data = this.findData(player);
        if (data != null) {
            if (data.status == ServerLOS.UpdateStatus.ReadyInLOS || data.status == ServerLOS.UpdateStatus.ReadyInMain) {
                long nowNanos = System.nanoTime();
                long intervalNanos = this.getObjectLosIntervalNanos(player);
                if (data.lastObjectLosNanos != 0L && nowNanos - data.lastObjectLosNanos < intervalNanos) {
                    return;
                }
                if (data.status == ServerLOS.UpdateStatus.ReadyInLOS) {
                    this.noise("BusyInMain playerID=" + player.onlineId);
                }

                data.status = ServerLOS.UpdateStatus.BusyInMain;
                try {
                    player.updateLOS();
                    data.lastObjectLosNanos = nowNanos;
                } finally {
                    data.status = ServerLOS.UpdateStatus.ReadyInMain;
                    synchronized (this.thread.notifier) {
                        this.thread.notifier.notify();
                    }
                }
            }
        }
    }

    /**
     * Builds one short-lived spatial index for all server players, then returns
     * only objects within (or just outside) the 96×96 ServerLOS visibility grid.
     * The one-chunk margin covers movement between index rebuilds.
     */
    public ArrayList<IsoMovingObject> getObjectCandidates(IsoPlayer player, IsoCell cell) {
        long nowNanos = System.nanoTime();
        if (this.candidateIndexCell != cell || nowNanos - this.candidateIndexBuiltNanos >= this.getCandidateIndexIntervalNanos(cell)) {
            this.rebuildObjectCandidateIndex(cell, nowNanos);
        }

        int centerChunkX = PZMath.fastfloor(player.getX() / 8.0F);
        int centerChunkY = PZMath.fastfloor(player.getY() / 8.0F);
        ArrayList<IsoMovingObject> candidates = new ArrayList<>();
        for (int chunkY = centerChunkY - CANDIDATE_RADIUS_CHUNKS;
                chunkY <= centerChunkY + CANDIDATE_RADIUS_CHUNKS; chunkY++) {
            for (int chunkX = centerChunkX - CANDIDATE_RADIUS_CHUNKS;
                    chunkX <= centerChunkX + CANDIDATE_RADIUS_CHUNKS; chunkX++) {
                ArrayList<IsoMovingObject> bucket = this.objectCandidatesByChunk.get(chunkKey(chunkX, chunkY));
                if (bucket != null) {
                    candidates.addAll(bucket);
                }
            }
        }
        return candidates;
    }

    private long getObjectLosIntervalNanos(IsoPlayer player) {
        IsoCell cell = player == null ? null : player.getCell();
        int objectCount = cell == null ? 0 : cell.getObjectList().size();
        int loadedCellCount = ServerMap.instance == null ? 0 : ServerMap.instance.loadedCells.size();
        long pressureNanos = 0L;
        pressureNanos += (long)Math.max(0, objectCount - 250) / 250L * 25_000_000L;
        pressureNanos += (long)Math.max(0, loadedCellCount - 8) * 5_000_000L;
        return Math.max(
                OBJECT_LOS_INTERVAL_BASE_NANOS,
                Math.min(OBJECT_LOS_INTERVAL_MAX_NANOS, OBJECT_LOS_INTERVAL_BASE_NANOS + pressureNanos)
        );
    }

    private long getCandidateIndexIntervalNanos(IsoCell cell) {
        int objectCount = cell == null ? 0 : cell.getObjectList().size();
        int loadedCellCount = ServerMap.instance == null ? 0 : ServerMap.instance.loadedCells.size();
        long pressureNanos = 0L;
        pressureNanos += (long)Math.max(0, objectCount - 250) / 250L * 15_000_000L;
        pressureNanos += (long)Math.max(0, loadedCellCount - 8) * 3_000_000L;
        return Math.max(
                CANDIDATE_INDEX_INTERVAL_BASE_NANOS,
                Math.min(CANDIDATE_INDEX_INTERVAL_MAX_NANOS, CANDIDATE_INDEX_INTERVAL_BASE_NANOS + pressureNanos)
        );
    }

    private void rebuildObjectCandidateIndex(IsoCell cell, long nowNanos) {
        this.objectCandidatesByChunk.clear();
        this.candidateIndexCell = cell;
        this.candidateIndexBuiltNanos = nowNanos;
        if (cell == null) {
            return;
        }

        ArrayList<IsoMovingObject> objects = new ArrayList<>(cell.getObjectList());
        for (int i = 0; i < objects.size(); i++) {
            IsoMovingObject object = objects.get(i);
            if (object == null) {
                continue;
            }
            int chunkX = PZMath.fastfloor(object.getX() / 8.0F);
            int chunkY = PZMath.fastfloor(object.getY() / 8.0F);
            this.objectCandidatesByChunk
                    .computeIfAbsent(chunkKey(chunkX, chunkY), ignored -> new ArrayList<>())
                    .add(object);
        }
    }

    private static long chunkKey(int chunkX, int chunkY) {
        return (long)chunkX << 32 | (long)chunkY & 4294967295L;
    }

    private ServerLOS.PlayerData findData(IsoPlayer player) {
        for (int i = 0; i < this.playersMain.size(); i++) {
            if (this.playersMain.get(i).player == player) {
                return this.playersMain.get(i);
            }
        }

        return null;
    }

    public void suspend() {
        this.mapLoading = true;
        this.wasSuspended = this.suspended;
        long start = System.currentTimeMillis();

        while (!this.suspended) {
            if (this.thread == null || !this.thread.isAlive()) {
                DebugType.General.println("ServerLOS.suspend timeout: LOS thread is not alive");
                break;
            }

            if (System.currentTimeMillis() - start >= SUSPEND_WAIT_TIMEOUT_MS) {
                DebugType.General.println("ServerLOS.suspend timeout after " + SUSPEND_WAIT_TIMEOUT_MS + "ms");
                break;
            }

            try {
                Thread.sleep(1L);
            } catch (InterruptedException var2) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!this.wasSuspended) {
            this.noise("suspend **********");
        }
    }

    public void resume() {
        this.mapLoading = false;
        synchronized (this.thread.notifier) {
            this.thread.notifier.notify();
        }

        if (!this.wasSuspended) {
            this.noise("resume **********");
        }
    }

    private class LOSThread extends Thread {
        public final Object notifier;

        private LOSThread() {
            Objects.requireNonNull(ServerLOS.this);
            super();
            this.notifier = new Object();
        }

        @Override
        public void run() {
            while (true) {
                try {
                    this.runInner();
                } catch (Throwable var2) {
                    DebugType.General.printException(var2, LogSeverity.Error);
                }
            }
        }

        private void runInner() {
            synchronized (ServerLOS.this.playersMain) {
                ServerLOS.this.playersLos.clear();
                ServerLOS.this.playersLos.addAll(ServerLOS.this.playersMain);
            }

            for (int i = 0; i < ServerLOS.this.playersLos.size(); i++) {
                ServerLOS.PlayerData data = ServerLOS.this.playersLos.get(i);
                if (data.status == ServerLOS.UpdateStatus.WaitingInLOS) {
                    data.status = ServerLOS.UpdateStatus.BusyInLOS;
                    ServerLOS.this.noise("BusyInLOS playerID=" + data.player.onlineId);
                    this.calcLOS(data);
                    data.status = ServerLOS.UpdateStatus.ReadyInLOS;
                }

                if (ServerLOS.this.mapLoading) {
                    break;
                }
            }

            while (this.shouldWait()) {
                ServerLOS.this.suspended = true;
                synchronized (this.notifier) {
                    try {
                        this.notifier.wait();
                    } catch (InterruptedException var4) {
                    }
                }
            }

            ServerLOS.this.suspended = false;
        }

        private void calcLOS(ServerLOS.PlayerData data) {
            boolean skip = data.px == PZMath.fastfloor(data.player.getX())
                && data.py == PZMath.fastfloor(data.player.getY())
                && data.pz == PZMath.fastfloor(data.player.getZ());
            data.px = PZMath.fastfloor(data.player.getX());
            data.py = PZMath.fastfloor(data.player.getY());
            data.pz = PZMath.fastfloor(data.player.getZ());
            data.player.initLightInfo2();
            if (!skip) {
                int playerIndex = 0;
                LosUtil.PerPlayerData ppd = LosUtil.cachedresults[0];
                ppd.checkSize();

                for (int x = 0; x < LosUtil.sizeX; x++) {
                    for (int y = 0; y < LosUtil.sizeY; y++) {
                        for (int z = 0; z < LosUtil.sizeZ; z++) {
                            ppd.cachedresults[x][y][z] = 0;
                        }
                    }
                }

                try {
                    IsoPlayer.players[0] = data.player;
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
                            for (int z = minZ; z < maxZ; z++) {
                                IsoGridSquare sq = ServerMap.instance.getGridSquare(x, y, z);
                                if (sq != null) {
                                    sq.CalcVisibility(0, isoGameCharacter, visibilityData);
                                    data.visible[x - minX][y - minY][z - minZ] = sq.isCouldSee(0);
                                    sq.checkRoomSeen(0);
                                } else {
                                    data.visible[x - minX][y - minY][z - minZ] = false;
                                }
                            }
                        }
                    }
                } finally {
                    IsoPlayer.players[0] = null;
                }
            }
        }

        private boolean shouldWait() {
            if (ServerLOS.this.mapLoading) {
                return true;
            } else {
                for (int i = 0; i < ServerLOS.this.playersLos.size(); i++) {
                    ServerLOS.PlayerData data = ServerLOS.this.playersLos.get(i);
                    if (data.status == ServerLOS.UpdateStatus.WaitingInLOS) {
                        return false;
                    }
                }

                synchronized (ServerLOS.this.playersMain) {
                    return ServerLOS.this.playersLos.size() == ServerLOS.this.playersMain.size();
                }
            }
        }
    }

    private static final class PlayerData {
        public IsoPlayer player;
        public ServerLOS.UpdateStatus status = ServerLOS.UpdateStatus.NeverDone;
        public int px;
        public int py;
        public int pz;
        public long lastObjectLosNanos;
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
