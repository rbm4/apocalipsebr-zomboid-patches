package cloud.apocalipse.pz.agent.experimental.chunks;

import cloud.apocalipse.pz.agent.experimental.chunks.ChunkObjectMutationWriter.Change;
import cloud.apocalipse.pz.agent.experimental.chunks.ChunkObjectMutationWriter.Location;
import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.WeakHashMap;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/** Coalesces ordinary-object changes on the world owner and hands off detached bytes. */
public final class ChunkObjectMutations {
    private static final int MAX_DIRTY = 1024;
    private static final int MAX_IDENTITIES = 8192;
    private static final int MAX_PER_FRAME = 32;
    private static final long CAPTURE_BUDGET_NANOS = 1_000_000;

    private static final WeakHashMap<IsoObject, Identity> identities = new WeakHashMap<>();
    private static final LinkedHashMap<UUID, Pending> dirty = new LinkedHashMap<>();
    private static final ByteBuffer buffer = ByteBuffer.allocate(65536);
    private static ChunkObjectMutationWriter writer;
    private static long revision;
    private static boolean capturing;
    private static boolean stopped;

    private record Pending(IsoObject object, IsoGridSquare square, short loadId, Location removed) {}
    private record Identity(UUID id, short loadId) {}
    private record Removal(Location location, short loadId) {}

    private ChunkObjectMutations() {
    }

    static void initialize(String dataset) {
        writer = new ChunkObjectMutationWriter(dataset);
        PostgresRuntime.beforeShutdown(writer::close);
        writer.start();
    }

    private static boolean onOwner() {
        return !stopped && !capturing && GameServer.server && Thread.currentThread() == GameServer.mainThread;
    }

    private static IsoGridSquare liveSquare(IsoObject object) {
        if (object == null || object.getClass() != IsoObject.class || !object.Serialize()) return null;
        IsoGridSquare square = object.getSquare();
        if (square == null) return null;
        IsoChunk chunk = square.getChunk();
        if (chunk == null || !chunk.loaded || chunk.preventHotSave
                || ServerMap.instance.getGridSquare(square.getX(), square.getY(), square.getZ()) != square) return null;
        return square;
    }

    public static void changed(Object changed) {
        if (!onOwner()) return;
        IsoObject object = (IsoObject) changed;
        IsoGridSquare square = liveSquare(object);
        if (square != null && object.getObjectIndex() >= 0) {
            short loadId = square.getChunk().getLoadID();
            Identity identity = identities.get(object);
            if (identity == null || identity.loadId() != loadId) {
                if (identity == null && identities.size() >= MAX_IDENTITIES) {
                    writer.recordGap();
                    return;
                }
                identity = new Identity(UUID.randomUUID(), loadId);
                identities.put(object, identity);
            }
            enqueue(identity.id(), new Pending(object, square, loadId, null));
        }
    }

    public static Object beforeRemoval(Object removed) {
        if (!onOwner()) return null;
        IsoObject object = (IsoObject) removed;
        IsoGridSquare square = liveSquare(object);
        int index = square == null ? -1 : object.getObjectIndex();
        return index < 0 ? null : new Removal(location(object, square, index), square.getChunk().getLoadID());
    }

    public static void removed(Object removed, Object location) {
        if (location == null || !onOwner()) return;
        var object = (IsoObject) removed;
        if (object.getObjectIndex() >= 0) return;
        var removal = (Removal) location;
        Identity identity = identities.remove(object);
        UUID id = identity == null || identity.loadId() != removal.loadId() ? UUID.randomUUID() : identity.id();
        enqueue(id, new Pending(null, null, (short) -1, removal.location()));
    }

    public static void reset(Object reset) {
        if (!onOwner()) return;
        Identity identity = identities.remove((IsoObject) reset);
        if (identity != null && dirty.remove(identity.id()) != null) writer.recordGap();
    }

    private static void enqueue(UUID id, Pending pending) {
        if (dirty.size() >= MAX_DIRTY && !dirty.containsKey(id)) {
            writer.recordGap();
            return;
        }
        dirty.put(id, pending);
    }

    public static void captureFrame() {
        if (!onOwner() || dirty.isEmpty()) return;
        long deadline = System.nanoTime() + CAPTURE_BUDGET_NANOS;
        capture(MAX_PER_FRAME, deadline);
    }

    public static void finishCapture() {
        if (!onOwner()) return;
        capture(MAX_PER_FRAME, System.nanoTime() + CAPTURE_BUDGET_NANOS);
        stopped = true;
        for (int i = 0; i < dirty.size(); i++) writer.recordGap();
        dirty.clear();
        identities.clear();
    }

    private static void capture(int limit, long deadline) {
        capturing = true;
        try {
            var entries = dirty.entrySet().iterator();
            for (int count = 0; count < limit && entries.hasNext() && System.nanoTime() < deadline; count++) {
                var entry = entries.next();
                captureObject(entry.getKey(), entry.getValue());
                entries.remove();
            }
        } finally {
            capturing = false;
        }
    }

    private static void captureObject(UUID id, Pending pending) {
        if (pending.removed() != null) {
            writer.offer(new Change(id, ++revision, pending.removed(), null));
            return;
        }
        IsoObject object = pending.object();
        IsoGridSquare square = liveSquare(object);
        int index = square == null ? -1 : object.getObjectIndex();
        if (index < 0 || square != pending.square() || square.getChunk().getLoadID() != pending.loadId()) {
            writer.recordGap();
            return;
        }
        try {
            buffer.clear();
            object.save(buffer, false);
            writer.offer(new Change(id, ++revision, location(object, square, index),
                Arrays.copyOf(buffer.array(), buffer.position())));
        } catch (IOException | RuntimeException failed) {
            writer.recordGap();
        }
    }

    private static Location location(IsoObject object, IsoGridSquare square, int index) {
        return new Location(square.getX(), square.getY(), square.getZ(), index, square.getSpecialObjects().contains(object));
    }
}
