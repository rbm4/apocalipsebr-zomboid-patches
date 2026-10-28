package zombie.popman;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import zombie.ApocBRServerTelemetryLite;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

/** Main-thread coverage reconciliation; unchanged coverage retains its buckets. */
final class ZombieAuthCoverage {
    private static final int CELL_SIZE = 64;
    static final class Candidate {
        final UdpConnection connection;
        final IsoPlayer player;
        int relevantRange, minX, maxX, minY, maxY, order;
        Candidate(UdpConnection connection, IsoPlayer player) { this.connection = connection; this.player = player; }
    }
    private static final class Coverage {
        final Candidate[] players = new Candidate[4];
        long seen;
        boolean eligible;
    }
    private static final class Bucket {
        final ArrayList<Candidate> candidates = new ArrayList<>();
        boolean dirty;
    }
    private static final Comparator<Candidate> ORDER = Comparator.comparingInt(c -> c.order);
    private final IdentityHashMap<UdpConnection, Coverage> connections = new IdentityHashMap<>();
    private final HashMap<Long, Bucket> buckets = new HashMap<>();
    private long generation;

    boolean eligible(UdpConnection connection) {
        Coverage coverage = this.connections.get(connection);
        return coverage != null && coverage.seen == this.generation && coverage.eligible;
    }

    void refresh() {
        this.generation++;
        long changed = 0, playersChecked = 0;
        for (int n = 0; n < GameServer.udpEngine.connections.size(); n++) {
            UdpConnection connection = GameServer.udpEngine.connections.get(n);
            if (connection == null) continue;
            Coverage coverage = this.connections.computeIfAbsent(connection, c -> new Coverage());
            coverage.seen = this.generation;
            coverage.eligible = connection.isFullyConnected() && !GameServer.isDelayedDisconnect(connection);
            int range = connection.getRelevantRange() - 2;
            float radius = range * 8.0F;
            for (int slot = 0; slot < 4; slot++) {
                playersChecked++;
                IsoPlayer player = connection.players[slot];
                Candidate previous = coverage.players[slot];
                boolean eligible = coverage.eligible && player != null && player.isAlive()
                    && Float.isFinite(player.getX()) && Float.isFinite(player.getY()) && range >= 0;
                if (!eligible || previous != null && previous.player != player) {
                    if (previous != null) { this.unlink(previous); coverage.players[slot] = null; changed++; }
                    if (!eligible) continue;
                    previous = null;
                }
                int minX = cell(player.getX() - radius), maxX = cell(player.getX() + radius);
                int minY = cell(player.getY() - radius), maxY = cell(player.getY() + radius);
                boolean moved = previous == null || previous.minX != minX || previous.maxX != maxX
                    || previous.minY != minY || previous.maxY != maxY || previous.relevantRange != range;
                if (moved) {
                    if (previous != null) this.unlink(previous);
                    else previous = new Candidate(connection, player);
                    previous.minX = minX; previous.maxX = maxX; previous.minY = minY; previous.maxY = maxY;
                    previous.relevantRange = range;
                    previous.order = n * 4 + slot;
                    this.link(previous);
                    coverage.players[slot] = previous;
                    changed++;
                } else if (previous.order != n * 4 + slot) {
                    previous.order = n * 4 + slot;
                    for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) this.buckets.get(key(x,y)).dirty = true;
                }
            }
        }
        var iterator = this.connections.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().seen == this.generation) continue;
            for (Candidate candidate : entry.getValue().players) if (candidate != null) { this.unlink(candidate); changed++; }
            iterator.remove();
        }
        ApocBRServerTelemetryLite.count("zombies.auth.coveragePlayersChecked", playersChecked);
        ApocBRServerTelemetryLite.count("zombies.auth.coverageChanged", changed);
    }

    List<Candidate> candidates(float x, float y) {
        Bucket bucket = this.buckets.get(key(cell(x), cell(y)));
        if (bucket == null) return List.of();
        if (bucket.dirty) { bucket.candidates.sort(ORDER); bucket.dirty = false; }
        return bucket.candidates;
    }

    private void link(Candidate candidate) {
        for (int x = candidate.minX; x <= candidate.maxX; x++) for (int y = candidate.minY; y <= candidate.maxY; y++) {
            Bucket bucket = this.buckets.computeIfAbsent(key(x,y), k -> new Bucket());
            bucket.candidates.add(candidate);
            bucket.dirty = true;
        }
    }
    private void unlink(Candidate candidate) {
        for (int x = candidate.minX; x <= candidate.maxX; x++) for (int y = candidate.minY; y <= candidate.maxY; y++) {
            long key = key(x,y);
            Bucket bucket = this.buckets.get(key);
            bucket.candidates.remove(candidate);
            if (bucket.candidates.isEmpty()) this.buckets.remove(key);
        }
    }
    private static int cell(float value) { return PZMath.fastfloor(value / CELL_SIZE); }
    private static long key(int x, int y) { return ((long)x << 32) | (y & 0xffffffffL); }
}
