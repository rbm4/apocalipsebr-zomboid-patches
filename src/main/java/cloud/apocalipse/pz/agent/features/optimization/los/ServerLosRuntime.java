package cloud.apocalipse.pz.agent.features.optimization.los;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import zombie.characters.IsoGameCharacter.LightInfo;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.LosUtil;
import zombie.meta.Meta;
import zombie.network.ServerLOS;
import zombie.network.ServerMap;

final class ServerLosRuntime {
    private static final int WIDTH = 96;
    private static final int HALF_WIDTH = WIDTH / 2;
    private static final float MAX_MOVEMENT_SQUARED = 4.0F;
    private final IdentityHashMap<IsoPlayer, PlayerState> players = new IdentityHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ArrayBlockingQueue<Integer> slots;
    private final long intervalNanos;
    private int running;
    private boolean suspended;
    private boolean closed;

    ServerLosRuntime() {
        int slotCount = LosUtil.cachedresults.length;
        int threads = Math.clamp(setting(AgentConfiguration.LOS_WORKER_THREADS_PROPERTY, slotCount), 1, slotCount);
        int capacity = Math.clamp(setting(AgentConfiguration.LOS_QUEUE_CAPACITY_PROPERTY, 256), 16, 4096);
        int interval = Math.max(250, setting(AgentConfiguration.LOS_INTERVAL_PROPERTY, 2000));
        intervalNanos = TimeUnit.MILLISECONDS.toNanos(interval);
        slots = new ArrayBlockingQueue<>(slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            slots.add(slot);
        }
        workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().daemon().name("ApocBR-ServerLOS-", 1).factory());
    }

    private static int setting(String name, int fallback) {
        try {
            return Integer.parseInt(AgentConfiguration.systemProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    synchronized void add(IsoPlayer player) {
        if (!closed) {
            players.computeIfAbsent(player, ignored -> new PlayerState());
        }
    }

    synchronized void remove(IsoPlayer player) {
        PlayerState state = players.remove(player);
        if (state != null) {
            cancel(state);
        }
    }

    synchronized void schedule(IsoPlayer player) {
        PlayerState state = players.get(player);
        long now = System.nanoTime();
        if (closed || suspended || state == null || state.job != null || state.completed != null
                || (state.nextDue != 0 && now - state.nextDue < 0)) {
            return;
        }

        player.initLightInfo2();
        LightInfo light = player.getLightInfo2();
        Result result = state.spare == null ? new Result() : state.spare;
        state.spare = null;
        result.reset(PZMath.fastfloor(player.getX()), PZMath.fastfloor(player.getY()), PZMath.fastfloor(player.getZ()),
            LosUtil.sizeZ);
        Job job = new Job(state, result, ServerMap.instance, light.square != null,
            PZMath.fastfloor(light.x), PZMath.fastfloor(light.y), PZMath.fastfloor(light.z));
        state.job = job;
        try {
            workers.execute(job);
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.LOS_QUEUED);
        } catch (RejectedExecutionException busy) {
            state.job = null;
            state.spare = result;
            state.nextDue = now + intervalNanos;
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.LOS_QUEUE_REJECTED);
        }
    }

    synchronized boolean isVisible(IsoPlayer player, IsoGridSquare square) {
        PlayerState state = players.get(player);
        return state != null && state.visible != null && state.visible.contains(square.x, square.y, square.z);
    }

    void apply(IsoPlayer player) {
        Result result;
        PlayerState state;
        synchronized (this) {
            state = players.get(player);
            if (state == null || state.completed == null || suspended) {
                return;
            }
            result = state.completed;
            state.completed = null;
            float dx = player.getX() - result.x;
            float dy = player.getY() - result.y;
            if (PZMath.fastfloor(player.getZ()) != result.z || !(dx * dx + dy * dy <= MAX_MOVEMENT_SQUARED)
                    || result.map != ServerMap.instance) {
                state.spare = result;
                state.nextDue = 0;
                OptimizationTelemetry.count(OptimizationTelemetry.Counter.LOS_STALE);
                return;
            }
            state.spare = state.visible;
            state.visible = result;
            state.nextDue = System.nanoTime() + intervalNanos;
        }

        long started = System.nanoTime();
        try {
            for (int index = result.rooms.nextSetBit(0); index >= 0; index = result.rooms.nextSetBit(index + 1)) {
                IsoGridSquare square = result.map.getGridSquare(result.x - HALF_WIDTH + index / (WIDTH * result.depth),
                    result.y - HALF_WIDTH + index / result.depth % WIDTH, result.z - result.depth / 2 + index % result.depth);
                if (square != null) {
                    checkRoomSeen(player, square);
                    Meta.instance.dealWithSquareSeen(square);
                }
            }
            player.updateLOS();
        } finally {
            OptimizationTelemetry.Phase.LOS_COMPLETION.recordSince(started);
        }
    }

    private static void checkRoomSeen(IsoPlayer player, IsoGridSquare square) {
        var room = square.getRoom();
        if (room == null || room.def == null || room.def.explored || square.z != PZMath.fastfloor(player.getZ())) {
            return;
        }
        int range = player.getBuilding() == room.building ? 50 : 10;
        if (IsoUtils.DistanceToSquared(player.getX(), player.getY(), square.x + 0.5F, square.y + 0.5F) < range * range) {
            room.def.explored = true;
            room.onSee();
            room.seen = 0;
            if (player.isLocalPlayer()) {
                player.triggerMusicIntensityEvent("SeeUnexploredRoom");
            }
        }
    }

    synchronized void suspend() {
        long started = System.nanoTime();
        suspended = true;
        workers.getQueue().clear();
        for (PlayerState state : players.values()) {
            cancel(state);
            state.nextDue = 0;
            state.visible = null;
            state.completed = null;
        }
        awaitIdle();
        OptimizationTelemetry.Phase.LOS_SUSPEND.recordSince(started);
    }

    synchronized void resume() {
        if (!closed) {
            suspended = false;
        }
    }

    synchronized void close() {
        closed = true;
        suspend();
        players.clear();
        workers.shutdown();
    }

    private void awaitIdle() {
        boolean interrupted = false;
        while (running != 0) {
            try {
                wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void cancel(PlayerState state) {
        if (state.job != null) {
            state.job.cancelled = true;
            workers.remove(state.job);
            state.job = null;
        }
    }

    private static final class PlayerState {
        private Job job;
        private Result completed;
        private Result visible;
        private Result spare;
        private long nextDue;
    }

    private static final class Result {
        private final BitSet visible = new BitSet();
        private final BitSet rooms = new BitSet();
        private ServerMap map;
        private int x;
        private int y;
        private int z;
        private int depth;

        void reset(int x, int y, int z, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.depth = depth;
            visible.clear();
            rooms.clear();
        }

        boolean contains(int worldX, int worldY, int worldZ) {
            int ix = worldX - x + HALF_WIDTH;
            int iy = worldY - y + HALF_WIDTH;
            int iz = worldZ - z + depth / 2;
            return ix >= 0 && ix < WIDTH && iy >= 0 && iy < WIDTH && iz >= 0 && iz < depth
                && visible.get((ix * WIDTH + iy) * depth + iz);
        }
    }

    private final class Job implements Runnable {
        private final PlayerState state;
        private final Result result;
        private final ServerMap map;
        private final boolean hasLightSquare;
        private final int lightX;
        private final int lightY;
        private final int lightZ;
        private volatile boolean cancelled;

        Job(PlayerState state, Result result, ServerMap map, boolean hasLightSquare, int lightX, int lightY, int lightZ) {
            this.state = state;
            this.result = result;
            this.map = map;
            this.hasLightSquare = hasLightSquare;
            this.lightX = lightX;
            this.lightY = lightY;
            this.lightZ = lightZ;
        }

        @Override
        public void run() {
            synchronized (ServerLosRuntime.this) {
                if (cancelled || suspended || closed) {
                    return;
                }
                running++;
            }

            int slot = slots.remove();
            long started = System.nanoTime();
            boolean finished = false;
            try {
                calculate(slot);
                finished = !cancelled;
            } catch (RuntimeException failure) {
                System.err.println("[ApocBR-Agent][ServerLOS] Calculation failed: " + failure);
            } finally {
                slots.add(slot);
                OptimizationTelemetry.Phase.LOS_WORK.recordSince(started);
                synchronized (ServerLosRuntime.this) {
                    if (state.job == this) {
                        state.job = null;
                        if (finished && !suspended && !closed) {
                            state.completed = result;
                        } else {
                            state.spare = result;
                            state.nextDue = System.nanoTime() + intervalNanos;
                        }
                    }
                    running--;
                    ServerLosRuntime.this.notifyAll();
                }
            }
        }

        private void calculate(int slot) {
            result.map = map;
            if (!hasLightSquare || map == null) {
                return;
            }

            var cache = LosUtil.cachedresults[slot];
            cache.checkSize();
            for (byte[][] column : cache.cachedresults) {
                for (byte[] row : column) {
                    Arrays.fill(row, (byte) 0);
                }
            }

            int index = 0;
            int minZ = result.z - result.depth / 2;
            for (int x = result.x - HALF_WIDTH; x < result.x + HALF_WIDTH; x++) {
                for (int y = result.y - HALF_WIDTH; y < result.y + HALF_WIDTH; y++) {
                    if (cancelled) {
                        return;
                    }
                    for (int z = minZ; z < minZ + result.depth; z++, index++) {
                        IsoGridSquare square = map.getGridSquare(x, y, z);
                        // The vanilla dedicated-server bCouldSee bit is independent of facing.
                        if (square != null && square.getChunk() != null
                                && LosUtil.lineClearCached(square.getCell(), x, y, z, lightX, lightY, lightZ,
                                    false, slot) != LosUtil.TestResults.Blocked) {
                            result.visible.set(index);
                            if (square.getRoom() != null) {
                                result.rooms.set(index);
                            }
                        }
                    }
                }
            }
        }
    }
}
