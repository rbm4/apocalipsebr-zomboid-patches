package zombie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import zombie.iso.IsoMovingObject;

/** Main-thread spatial membership, refreshed after zombie workers have finished. */
public final class ServerAnimalPerceptionGrid {
    private static final int CELL_SIZE = 16;
    private static final float ZOMBIE_RADIUS = 10.0F;
    private static final Comparator<ServerMovingObjectIndex.Member> ORDER = Comparator.comparingInt(m -> m.typeSlot);

    static final class Entry {
        final ServerMovingObjectIndex.Member member;
        final ArrayList<Entry> bucket;
        final long key;
        int slot;

        Entry(ServerMovingObjectIndex.Member member, ArrayList<Entry> bucket, long key) {
            this.member = member;
            this.bucket = bucket;
            this.key = key;
            this.slot = bucket.size();
            bucket.add(this);
        }
    }

    private final HashMap<Long, ArrayList<Entry>> buckets = new HashMap<>();
    private final ArrayList<Entry> humans = new ArrayList<>();
    private final ArrayList<ServerMovingObjectIndex.Member> candidates = new ArrayList<>();
    private long refreshedFrame = Long.MIN_VALUE;

    void invalidate() { this.refreshedFrame = Long.MIN_VALUE; }

    void addHuman(ServerMovingObjectIndex.Member member) {
        // Type-only lifecycle registration is safe during a subclass constructor.
        member.perceptionEntry = new Entry(member, this.humans, 0L);
    }

    void remove(ServerMovingObjectIndex.Member member) {
        Entry entry = member.perceptionEntry;
        if (entry == null) return;
        int last = entry.bucket.size() - 1;
        Entry moved = entry.bucket.remove(last);
        if (entry.slot != last) {
            entry.bucket.set(entry.slot, moved);
            moved.slot = entry.slot;
        }
        if (member.kind == ServerMovingObjectIndex.ZOMBIE && entry.bucket.isEmpty()) this.buckets.remove(entry.key);
        member.perceptionEntry = null;
    }

    private static int cell(float coordinate) {
        return (int)Math.floor(coordinate / CELL_SIZE);
    }

    private static long key(int x, int y) {
        return ((long)x << 32) | (y & 0xffffffffL);
    }

    private void refresh(List<ServerMovingObjectIndex.Member> targets, long frame) {
        if (this.refreshedFrame == frame) return;
        long started = System.nanoTime();
        long checked = 0, relocated = 0;
        for (ServerMovingObjectIndex.Member member : targets) {
            if (member == null || !member.active) continue;
            if (member.kind == ServerMovingObjectIndex.HUMAN) continue;
            checked++;
            IsoMovingObject object = member.object;
            if (!Float.isFinite(object.getX()) || !Float.isFinite(object.getY())) {
                this.remove(member);
                continue;
            }
            long key = key(cell(object.getX()), cell(object.getY()));
            if (member.perceptionEntry != null && member.perceptionEntry.key == key) continue;
            this.remove(member);
            member.perceptionEntry = new Entry(member, this.buckets.computeIfAbsent(key, k -> new ArrayList<>()), key);
            relocated++;
        }
        this.refreshedFrame = frame;
        ApocBRServerTelemetryLite.count("animals.perceptionGridChecked", checked);
        ApocBRServerTelemetryLite.count("animals.perceptionGridRelocated", relocated);
        ApocBRServerTelemetryLite.recordPhase("simulation.animals.perceptionGrid", System.nanoTime() - started);
    }

    void query(List<ServerMovingObjectIndex.Member> targets, long frame, float x, float y,
               boolean includeZombies, ArrayList<IsoMovingObject> output) {
        if (includeZombies) this.refresh(targets, frame);
        output.clear();
        this.candidates.clear();
        long visited = 0;
        if (includeZombies && Float.isFinite(x) && Float.isFinite(y)) {
            int minX = cell(x - ZOMBIE_RADIUS), maxX = cell(x + ZOMBIE_RADIUS);
            int minY = cell(y - ZOMBIE_RADIUS), maxY = cell(y + ZOMBIE_RADIUS);
            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    ArrayList<Entry> bucket = this.buckets.get(key(bx, by));
                    if (bucket == null) continue;
                    for (Entry entry : bucket) {
                        visited++;
                        IsoMovingObject object = entry.member.object;
                        float dx = object.getX() - x, dy = object.getY() - y;
                        if (entry.member.active && dx * dx + dy * dy <= ZOMBIE_RADIUS * ZOMBIE_RADIUS) {
                            this.candidates.add(entry.member);
                        }
                    }
                }
            }
        }
        // Human detection has distant branches; preserve its unrestricted candidate set.
        for (Entry entry : this.humans) if (entry.member.active) this.candidates.add(entry.member);
        this.candidates.sort(ORDER);
        for (ServerMovingObjectIndex.Member member : this.candidates) output.add(member.object);
        this.candidates.clear();
        ApocBRServerTelemetryLite.count("animals.perceptionBucketCandidatesVisited", visited);
    }
}
