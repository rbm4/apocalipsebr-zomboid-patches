// Patched ChunkUpdateTask.java - Stale-chunk guard before native updateChunk call.
//
// Root issue: libPZPathFind64.so's Square::init() can receive a garbage 'this'
// pointer (observed: 0x30) and SIGSEGV the entire JVM when a ChunkUpdateTask
// executes after the corresponding chunk has already been removed from native
// pathfind state. This can happen when:
//   1. A ChunkUpdateTask is enqueued for chunk (wx, wy) with loadId=N.
//   2. The chunk is quickly unloaded and a ChunkRemoveTask is enqueued.
//   3. The chunk is reloaded with a new loadId=M, another ChunkUpdateTask queued.
//   4. Under queue backlog (server under load), tasks from a previous load cycle
//      reach execute() after native state for that slot has been torn down.
//
// Fix: before calling updateChunk, look up whether this (wx, wy) is still
// registered in PathfindNative.activeChunkLoadIds with our exact loadId.
// If the entry is absent (chunk removed) or has a different loadId (re-added),
// we skip the stale native call.
//
// Thread safety: activeChunkLoadIds is a ConcurrentHashMap updated on the
// main thread (in addChunkToWorld / removeChunkFromWorld) and read here on
// the pathfind thread. No locking needed; the worst case is processing a task
// that was just de-registered within the same scheduler tick, which is fine.
//
// Original: zombie.pathfind.nativeCode.ChunkUpdateTask (Build 42.19)
package zombie.pathfind.nativeCode;

import java.nio.ByteBuffer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.popman.ObjectPool;

class ChunkUpdateTask implements IPathfindTask {
    protected static final int SQUARES_PER_CHUNK = 8;
    protected static final int LEVELS_PER_CHUNK = 64;
    int wx;
    int wy;
    short loadId;
    ByteBuffer bb;
    static ByteBuffer bbTemp;
    private static final int BLOCK_SIZE = 256;
    static final ObjectPool<ChunkUpdateTask> pool = new ObjectPool<>(ChunkUpdateTask::new);

    private static int bufferSize(int size) {
        return (size + 256 - 1) / 256 * 256;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer bb, int capacity) {
        if (bb == null || bb.capacity() < capacity) {
            bb = ByteBuffer.allocateDirect(bufferSize(capacity));
        }
        return bb;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer bb) {
        if (bb == null) {
            return ByteBuffer.allocateDirect(256);
        } else if (bb.capacity() - bb.position() < 256) {
            ByteBuffer newBB = ensureCapacity(null, bb.position() + 256);
            newBB.put(0, bb, 0, bb.position());
            return newBB.position(bb.position());
        } else {
            return bb;
        }
    }

    ChunkUpdateTask init(IsoChunk chunk) {
        this.wx = chunk.wx;
        this.wy = chunk.wy;
        this.loadId = chunk.getLoadID();
        this.bb = ensureCapacity(this.bb);
        this.bb.clear();
        this.bb.putInt(chunk.minLevel + 32);
        this.bb.putInt(chunk.maxLevel + 32);
        bbTemp = ensureCapacity(bbTemp);
        bbTemp.clear();
        int numSlopedSurfaces = 0;

        for (int z = chunk.minLevel; z <= chunk.maxLevel; z++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    this.bb = ensureCapacity(this.bb);
                    IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                    if (sq == null) {
                        this.bb.putInt(0);
                        this.bb.putShort((short) 0);
                    } else {
                        int bits = SquareUpdateTask.getBits(sq);
                        short cost = SquareUpdateTask.getCost(sq);
                        this.bb.putInt(bits);
                        this.bb.putShort(cost);
                        IsoDirections slopeDir = sq.getSlopedSurfaceDirection();
                        if (slopeDir != null) {
                            bbTemp = ensureCapacity(bbTemp);
                            bbTemp.put((byte) x);
                            bbTemp.put((byte) y);
                            bbTemp.put((byte) (z + 32));
                            bbTemp.put((byte) slopeDir.ordinal());
                            bbTemp.putFloat(sq.getSlopedSurfaceHeightMin());
                            bbTemp.putFloat(sq.getSlopedSurfaceHeightMax());
                            numSlopedSurfaces++;
                        }
                    }
                }
            }
        }

        this.bb.putShort((short) numSlopedSurfaces);
        if (numSlopedSurfaces > 0) {
            int numBytes = this.bb.position() + bbTemp.position();
            if (numBytes > this.bb.capacity()) {
                ByteBuffer newBB = ByteBuffer.allocateDirect(bufferSize(numBytes));
                newBB.put(0, this.bb, 0, this.bb.position());
                newBB.position(this.bb.position());
                this.bb = newBB;
            }
            this.bb.put(this.bb.position(), bbTemp, 0, bbTemp.position());
            this.bb.position(this.bb.position() + bbTemp.position());
        }

        this.bb.flip();
        return this;
    }

    @Override
    public void execute() {
        // === ApocBR stale-chunk guard =========================================
        // Before issuing the native call, confirm that (wx, wy) is still
        // registered with the same loadId that was captured when this task was
        // created. If the chunk was removed (entry absent) or reloaded with a
        // new loadId (entry differs), skip the call to avoid the SIGSEGV in
        // libPZPathFind64.so Square::init() with a garbage 'this' pointer.
        Short activeLoadId = PathfindNative.activeChunkLoadIds.get(
                PathfindNative.chunkKey(this.wx, this.wy));
        if (activeLoadId == null || activeLoadId.shortValue() != this.loadId) {
            return;
        }
        // ======================================================================
        PathfindNative.updateChunk(this.loadId, this.wx, this.wy, this.bb);
    }

    static ChunkUpdateTask alloc() {
        return pool.alloc();
    }

    @Override
    public void release() {
        pool.release(this);
    }
}

