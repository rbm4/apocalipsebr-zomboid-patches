package zombie.popman.animal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import zombie.ApocBRServerTelemetryLite;
import zombie.characters.animals.IsoAnimal;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;

/** Conservative broad phase only: the sender retains UdpConnection.RelevantTo. */
final class ServerAnimalUpdateCoverage {
    private static final int CELL_SIZE = 64;
    private final IdentityHashMap<UdpConnection, Coverage> connections = new IdentityHashMap<>();
    private final HashMap<Long, ArrayList<Coverage>> buckets = new HashMap<>();
    private final ArrayList<Coverage> fallback = new ArrayList<>();
    private long generation;
    private long animalStamp;
    private long routedPairs;

    void prepare(List<UdpConnection> current, Iterable<Short> changed) {
        this.generation++;
        for (UdpConnection connection : current) {
            if (connection == null || !connection.isFullyConnected()) continue;
            Coverage coverage = this.connections.computeIfAbsent(connection, key -> new Coverage());
            if (coverage.seen == this.generation) continue;
            coverage.seen = this.generation;
            coverage.animals.clear();
            float radius = (connection.getRelevantRange() - 2) * 10;
            for (int slot = 0; slot < 4; slot++) {
                var area = connection.connectArea[slot];
                if (area == null) this.rectangle(coverage, slot * 2, false, 0, 0, 0, 0);
                else {
                    int width = (int)area.z;
                    int minX = PZMath.fastfloor(area.x - width / 2) * 8;
                    int minY = PZMath.fastfloor(area.y - width / 2) * 8;
                    this.rectangle(coverage, slot * 2, true, minX, minY, minX + width * 8, minY + width * 8);
                }
                var position = connection.releventPos[slot];
                if (position == null || radius < 0) this.rectangle(coverage, slot * 2 + 1, false, 0, 0, 0, 0);
                else this.rectangle(coverage, slot * 2 + 1, true, position.x - radius, position.y - radius, position.x + radius, position.y + radius);
            }
            boolean needsFallback = false;
            for (Rectangle rectangle : coverage.rectangles) needsFallback |= rectangle.fallback;
            if (needsFallback != coverage.fallback) {
                coverage.fallback = needsFallback;
                if (needsFallback) this.fallback.add(coverage); else this.fallback.remove(coverage);
            }
        }
        var iterator = this.connections.entrySet().iterator();
        while (iterator.hasNext()) {
            Coverage coverage = iterator.next().getValue();
            if (coverage.seen == this.generation) continue;
            for (Rectangle rectangle : coverage.rectangles) this.unlink(coverage, rectangle);
            this.fallback.remove(coverage);
            iterator.remove();
        }
        long visited = 0;
        this.routedPairs = 0;
        for (Short id : changed) {
            IsoAnimal animal = AnimalInstanceManager.getInstance().get(id);
            if (animal == null) continue;
            long stamp = ++this.animalStamp;
            float x = animal.getX(), y = animal.getY();
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                for (Coverage coverage : this.connections.values()) this.route(coverage, id, stamp);
                visited += this.connections.size();
            } else {
                ArrayList<Coverage> candidates = this.buckets.get(key(cell(x), cell(y)));
                if (candidates != null) {
                    visited += candidates.size();
                    for (Coverage coverage : candidates) this.route(coverage, id, stamp);
                }
                visited += this.fallback.size();
                for (Coverage coverage : this.fallback) this.route(coverage, id, stamp);
            }
        }
        ApocBRServerTelemetryLite.count("animals.sync.coverageCandidatesVisited", visited);
        ApocBRServerTelemetryLite.count("animals.sync.candidatePairs", this.routedPairs);
    }

    List<Short> candidates(UdpConnection connection) {
        Coverage coverage = this.connections.get(connection);
        return coverage == null ? List.of() : coverage.animals;
    }

    private void route(Coverage coverage, Short id, long stamp) {
        if (coverage.routed != stamp) { coverage.routed = stamp; coverage.animals.add(id); this.routedPairs++; }
    }

    private void rectangle(Coverage coverage, int slot, boolean present, float lowX, float lowY, float highX, float highY) {
        Rectangle rectangle = coverage.rectangles[slot];
        boolean valid = present && lowX <= highX && lowY <= highY;
        int minX = cell(Math.nextDown(lowX)), minY = cell(Math.nextDown(lowY));
        int maxX = cell(Math.nextUp(highX)), maxY = cell(Math.nextUp(highY));
        boolean wildcard = valid && (!Float.isFinite(lowX) || !Float.isFinite(lowY) || !Float.isFinite(highX) || !Float.isFinite(highY)
            || minX > maxX || minY > maxY || (long)maxX - minX > 64 || (long)maxY - minY > 64);
        boolean linked = valid && !wildcard;
        if (rectangle.linked == linked && rectangle.fallback == wildcard && (!linked
            || rectangle.minX == minX && rectangle.minY == minY && rectangle.maxX == maxX && rectangle.maxY == maxY)) return;
        this.unlink(coverage, rectangle);
        rectangle.linked = linked; rectangle.fallback = wildcard;
        rectangle.minX = minX; rectangle.minY = minY; rectangle.maxX = maxX; rectangle.maxY = maxY;
        if (linked) {
            for (long x = minX; x <= maxX; x++) for (long y = minY; y <= maxY; y++) {
                this.buckets.computeIfAbsent(key((int)x, (int)y), ignored -> new ArrayList<>()).add(coverage);
            }
        }
        ApocBRServerTelemetryLite.count("animals.sync.coverageChanged", 1);
    }

    private void unlink(Coverage coverage, Rectangle rectangle) {
        if (!rectangle.linked) return;
        for (long x = rectangle.minX; x <= rectangle.maxX; x++) for (long y = rectangle.minY; y <= rectangle.maxY; y++) {
            long key = key((int)x, (int)y);
            ArrayList<Coverage> bucket = this.buckets.get(key);
            bucket.remove(coverage);
            if (bucket.isEmpty()) this.buckets.remove(key);
        }
        rectangle.linked = false;
    }

    private static int cell(float coordinate) { return PZMath.fastfloor(coordinate / CELL_SIZE); }
    private static long key(int x, int y) { return (long)x << 32 | y & 0xffffffffL; }
    private static final class Rectangle {
        int minX, minY, maxX, maxY;
        boolean linked, fallback;
    }
    private static final class Coverage {
        long seen, routed;
        boolean fallback;
        final ArrayList<Short> animals = new ArrayList<>();
        final Rectangle[] rectangles = new Rectangle[8];
        Coverage() { for (int i = 0; i < this.rectangles.length; i++) this.rectangles[i] = new Rectangle(); }
    }
}
