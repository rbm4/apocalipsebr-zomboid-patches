package zombie;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.util.list.MutationTrackedArrayList;
import zombie.vehicles.BaseVehicle;

/** Main-thread spatial views. Exact vanilla tests remain at the query boundary. */
public final class ServerPlayerSpatialQueries {
    private static final int CELL_SIZE = 16;
    public static final class Vehicles extends HashSet<BaseVehicle> {
        private long revision;
        @Override public boolean add(BaseVehicle v) { boolean changed = super.add(v); if (changed) revision++; return changed; }
        @Override public boolean remove(Object v) { boolean changed = super.remove(v); if (changed) revision++; return changed; }
        @Override public void clear() { super.clear(); revision++; }
        @Override public Iterator<BaseVehicle> iterator() {
            Iterator<BaseVehicle> delegate = super.iterator();
            return new Iterator<>() {
                public boolean hasNext() { return delegate.hasNext(); }
                public BaseVehicle next() { return delegate.next(); }
                public void remove() { delegate.remove(); revision++; }
            };
        }
    }
    private record ZombieEntry(IsoZombie zombie, int order) {}
    private final HashMap<Long, ArrayList<ZombieEntry>> zombieBuckets = new HashMap<>();
    private final ArrayList<ZombieEntry> zombieCandidates = new ArrayList<>();
    private final HashMap<Long, HashSet<BaseVehicle>> vehicleBuckets = new HashMap<>();
    private final IdentityHashMap<BaseVehicle, Long> vehicleKeys = new IdentityHashMap<>();
    private long zombieFrame = Long.MIN_VALUE, zombieRevision = Long.MIN_VALUE;
    private long vehicleFrame = Long.MIN_VALUE, vehicleRevision = Long.MIN_VALUE;
    private static int coordinate(float value) { return (int)Math.floor(value / CELL_SIZE); }
    private static long key(int x, int y) { return ((long)x << 32) | (y & 0xffffffffL); }
    private static boolean finite(IsoMovingObject object) { return Float.isFinite(object.getX()) && Float.isFinite(object.getY()); }

    public void queryZombies(IsoCell cell, float x, float y, float radius, ArrayList<IsoZombie> output) {
        ArrayList<IsoZombie> zombies = cell.getZombieList();
        output.clear();
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(radius)) {
            output.addAll(zombies);
            return;
        }
        int minX = coordinate(x - radius), maxX = coordinate(x + radius);
        int minY = coordinate(y - radius), maxY = coordinate(y + radius);
        long width = (long)maxX - minX + 1, height = (long)maxY - minY + 1;
        long cellBudget = Math.max(64L, (long)zombies.size() * 4);
        if (width <= 0 || height <= 0 || width > cellBudget || height > cellBudget / width) {
            // Preserve the complete snapshot for malformed/unusually large views.
            output.addAll(zombies);
            return;
        }
        long revision = ((MutationTrackedArrayList<IsoZombie>)zombies).mutationVersion();
        long frame = IsoWorld.instance.getFrameNo();
        if (zombieFrame != frame || zombieRevision != revision) {
            long started = System.nanoTime();
            zombieBuckets.clear();
            for (int i = 0; i < zombies.size(); i++) {
                IsoZombie zombie = zombies.get(i);
                if (zombie != null && finite(zombie)) zombieBuckets.computeIfAbsent(key(coordinate(zombie.getX()), coordinate(zombie.getY())), k -> new ArrayList<>()).add(new ZombieEntry(zombie, i));
            }
            zombieFrame = frame;
            zombieRevision = revision;
            ApocBRServerTelemetryLite.count("players.los.gridEntriesChecked", zombies.size());
            ApocBRServerTelemetryLite.recordPhase("simulation.players.losGrid", System.nanoTime() - started);
        }
        output.clear();
        zombieCandidates.clear();
        for (long bx = minX; bx <= maxX; bx++) {
            for (long by = minY; by <= maxY; by++) {
                ArrayList<ZombieEntry> bucket = zombieBuckets.get(key((int)bx, (int)by));
                if (bucket != null) zombieCandidates.addAll(bucket);
            }
        }
        zombieCandidates.sort(Comparator.comparingInt(ZombieEntry::order));
        for (ZombieEntry entry : zombieCandidates) output.add(entry.zombie());
        ApocBRServerTelemetryLite.count("players.los.bucketCandidates", output.size());
        zombieCandidates.clear();
    }
    public void invalidateZombies() { zombieFrame = Long.MIN_VALUE; }

    private void refreshVehicles(IsoCell cell) {
        Vehicles vehicles = (Vehicles)cell.getVehicles();
        long frame = IsoWorld.instance.getFrameNo();
        if (vehicleFrame == frame && vehicleRevision == vehicles.revision) return;
        long started = System.nanoTime();
        vehicleBuckets.clear();
        vehicleKeys.clear();
        for (BaseVehicle vehicle : vehicles) vehicleMoved(vehicle);
        vehicleFrame = frame;
        vehicleRevision = vehicles.revision;
        ApocBRServerTelemetryLite.count("players.vehicles.gridEntriesChecked", vehicles.size());
        ApocBRServerTelemetryLite.recordPhase("simulation.players.vehicleGrid", System.nanoTime() - started);
    }
    public void vehicleMoved(BaseVehicle vehicle) {
        Long old = vehicleKeys.get(vehicle);
        Long next = finite(vehicle) ? key(coordinate(vehicle.getX()), coordinate(vehicle.getY())) : null;
        if (java.util.Objects.equals(old, next)) return;
        if (old != null) {
            HashSet<BaseVehicle> bucket = vehicleBuckets.get(old);
            bucket.remove(vehicle);
            if (bucket.isEmpty()) vehicleBuckets.remove(old);
            vehicleKeys.remove(vehicle);
        }
        if (next != null) {
            vehicleBuckets.computeIfAbsent(next, k -> new HashSet<>()).add(vehicle);
            vehicleKeys.put(vehicle, next);
        }
    }
    public boolean nearVehicle(IsoCell cell, IsoGameCharacter player) {
        refreshVehicles(cell);
        if (!finite(player)) return false;
        long visited = 0;
        for (long bx = coordinate(player.getX() - 3.5F); bx <= coordinate(player.getX() + 3.5F); bx++) {
            for (long by = coordinate(player.getY() - 3.5F); by <= coordinate(player.getY() + 3.5F); by++) {
                HashSet<BaseVehicle> bucket = vehicleBuckets.get(key((int)bx, (int)by));
                if (bucket == null) continue;
                for (BaseVehicle vehicle : bucket) {
                    visited++;
                    // DistTo is Manhattan distance in this build, not Euclidean distance.
                    if (vehicle.DistTo(player) < 3.5F) {
                        ApocBRServerTelemetryLite.count("players.vehicles.candidatesVisited", visited);
                        return true;
                    }
                }
            }
        }
        ApocBRServerTelemetryLite.count("players.vehicles.candidatesVisited", visited);
        return false;
    }
}
