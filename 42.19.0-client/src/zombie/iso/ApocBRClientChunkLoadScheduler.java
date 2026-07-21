package zombie.iso;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.PriorityQueue;
import zombie.LoadGridsquarePerformanceWorkaround;
import zombie.Lua.LuaEventManager;
import zombie.Lua.MapObjects;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
import zombie.network.GameServer;

public final class ApocBRClientChunkLoadScheduler {
    public static final ApocBRClientChunkLoadScheduler instance = new ApocBRClientChunkLoadScheduler();
    private static final long BUDGET_NANOS = 4_000_000L;

    private final PriorityQueue<SquareTask> squareTasks = new PriorityQueue<>(new SquareTaskComparator());
    private final IdentityHashMap<IsoChunk, Integer> pendingSquaresPerChunk = new IdentityHashMap<>();
    private long taskSequence;

    private ApocBRClientChunkLoadScheduler() {
    }

    public void startChunkLoad(IsoChunk chunk) {
        if (GameServer.server || chunk == null) {
            return;
        }

        Integer count = this.pendingSquaresPerChunk.get(chunk);
        this.pendingSquaresPerChunk.put(chunk, (count == null ? 0 : count) + 1);
    }

    public void addSquare(IsoGridSquare square) {
        if (GameServer.server || square == null) {
            return;
        }

        IsoChunk chunk = square.getChunk();
        if (chunk == null) {
            return;
        }

        Integer count = this.pendingSquaresPerChunk.get(chunk);
        if (count == null) {
            this.pendingSquaresPerChunk.put(chunk, 1);
        } else {
            this.pendingSquaresPerChunk.put(chunk, count + 1);
        }

        float distSq = getNearestPlayerDistanceSq(square);
        this.squareTasks.add(new SquareTask(square, distSq, ++this.taskSequence));
    }

    public void endChunkLoad(IsoChunk chunk) {
        if (GameServer.server || chunk == null) {
            return;
        }

        this.decrementChunkLoad(chunk);
    }

    public void process() {
        if (GameServer.server) {
            return;
        }

        long start = System.nanoTime();
        while (!this.squareTasks.isEmpty()) {
            SquareTask task = this.squareTasks.poll();
            if (task != null) {
                task.run();
            }

            if (System.nanoTime() - start >= BUDGET_NANOS) {
                return;
            }
        }
    }

    private void decrementChunkLoad(IsoChunk chunk) {
        Integer count = this.pendingSquaresPerChunk.get(chunk);
        if (count == null) {
            return;
        }

        if (count <= 1) {
            this.pendingSquaresPerChunk.remove(chunk);
            chunk.apocBrFinalizeChunkLoad();
        } else {
            this.pendingSquaresPerChunk.put(chunk, count - 1);
        }
    }

    private static float getNearestPlayerDistanceSq(IsoGridSquare square) {
        float min = Float.MAX_VALUE;
        int x = square.getX();
        int y = square.getY();

        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer player = IsoPlayer.players[i];
            if (player != null) {
                float dx = x - player.getX();
                float dy = y - player.getY();
                float d = dx * dx + dy * dy;
                if (d < min) {
                    min = d;
                }
            }
        }

        return min;
    }

    private static final class SquareTask {
        final IsoGridSquare square;
        final float distanceSq;
        final long sequence;

        SquareTask(IsoGridSquare square, float distanceSq, long sequence) {
            this.square = square;
            this.distanceSq = distanceSq;
            this.sequence = sequence;
        }

        void run() {
            IsoChunk chunk = this.square.getChunk();
            if (chunk == null) {
                return;
            }

            try {
                this.finishObjectsForSquare();
                MapObjects.loadGridSquare(this.square);
                LuaEventManager.triggerEvent("LoadGridsquare", this.square);
                LoadGridsquarePerformanceWorkaround.LoadGridsquare(this.square);
            } catch (Throwable t) {
                ExceptionLogger.logException(t);
            }

            ApocBRClientChunkLoadScheduler.instance.decrementChunkLoad(chunk);
        }

        private void finishObjectsForSquare() {
            if (this.square.getObjects().isEmpty()) {
                return;
            }

            IsoObject[] objects = this.square.getObjects().getElements();
            int size = this.square.getObjects().size();
            for (int i = 0; i < size; i++) {
                IsoObject object = objects[i];
                if (object != null && object.getSquare() == this.square && object.getSquare().getChunk() != null) {
                    object.apocBrFinishClientAddToWorld();
                }
            }
        }
    }

    private static final class SquareTaskComparator implements Comparator<SquareTask> {
        @Override
        public int compare(SquareTask a, SquareTask b) {
            int c = Float.compare(a.distanceSq, b.distanceSq);
            if (c != 0) {
                return c;
            }

            return Long.compare(a.sequence, b.sequence);
        }
    }
}
