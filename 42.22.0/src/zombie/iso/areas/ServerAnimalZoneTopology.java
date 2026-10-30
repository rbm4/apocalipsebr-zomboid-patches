package zombie.iso.areas;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.HashMap;
import zombie.ApocBRServerTelemetryLite;

/** Public geometry/list writes are reconciled before using cached connectivity. */
final class ServerAnimalZoneTopology {
    private record Shape(DesignationZoneAnimal zone, int x, int y, int z, int w, int h) {
        Shape(DesignationZoneAnimal zone) { this(zone, zone.x, zone.y, zone.z, zone.w, zone.h); }
        boolean current(DesignationZoneAnimal other) {
            return zone == other && x == other.x && y == other.y && z == other.z && w == other.w && h == other.h;
        }
    }
    private final ArrayList<Shape> shapes = new ArrayList<>();
    private final IdentityHashMap<DesignationZoneAnimal, ArrayList<DesignationZoneAnimal>> connected = new IdentityHashMap<>();
    private int cachedReferences;
    private static final int MAX_REFERENCES = 65536;
    private record Cell(int x, int y, int z) {}
    private final HashMap<Cell, ArrayList<Shape>> spatial = new HashMap<>();
    private boolean spatialAvailable;

    void clear() { shapes.clear(); connected.clear(); spatial.clear(); spatialAvailable = false; cachedReferences = 0; }

    private void buildSpatial() {
        int references = 0;
        for (Shape shape : shapes) {
            int endX = shape.x + shape.w, endY = shape.y + shape.h;
            if (endX <= shape.x || endY <= shape.y) continue;
            int minX = Math.floorDiv(shape.x, 64), maxX = Math.floorDiv(endX - 1, 64);
            int minY = Math.floorDiv(shape.y, 64), maxY = Math.floorDiv(endY - 1, 64);
            long cells = ((long)maxX - minX + 1) * ((long)maxY - minY + 1);
            if (cells > MAX_REFERENCES - references) { spatial.clear(); return; }
            references += (int)cells;
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) {
                spatial.computeIfAbsent(new Cell(x, y, shape.z), key -> new ArrayList<>()).add(shape);
            }
        }
        spatialAvailable = true;
        ApocBRServerTelemetryLite.count("zones.topology.spatialEntriesBuilt", references);
    }

    /** Used only within a check immediately after get() validated public geometry. */
    DesignationZoneAnimal find(int x, int y, int z) {
        if (!spatialAvailable) return DesignationZoneAnimal.getZone(x, y, z);
        ArrayList<Shape> candidates = spatial.get(new Cell(Math.floorDiv(x, 64), Math.floorDiv(y, 64), z));
        if (candidates == null) return null;
        for (Shape shape : candidates) {
            if (x >= shape.x && x < shape.x + shape.w && y >= shape.y && y < shape.y + shape.h) return shape.zone;
        }
        return null;
    }

    ArrayList<DesignationZoneAnimal> get(ArrayList<DesignationZoneAnimal> result, DesignationZoneAnimal root) {
        long validationStarted = System.nanoTime();
        ArrayList<DesignationZoneAnimal> zones = DesignationZoneAnimal.designationAnimalZoneList;
        boolean changed = shapes.size() != zones.size(), registered = false;
        for (int i = 0; i < zones.size(); i++) {
            DesignationZoneAnimal zone = zones.get(i);
            registered |= zone == root;
            if (!changed && !shapes.get(i).current(zone)) changed = true;
        }
        ApocBRServerTelemetryLite.count("zones.topology.geometryChecked", zones.size());
        if (changed) {
            clear();
            for (DesignationZoneAnimal zone : zones) shapes.add(new Shape(zone));
            buildSpatial();
            ApocBRServerTelemetryLite.count("zones.topology.invalidated", 1L);
        }
        ApocBRServerTelemetryLite.recordPhase("simulation.designationZones.topologyValidate", System.nanoTime() - validationStarted);
        ArrayList<DesignationZoneAnimal> cached = registered ? connected.get(root) : null;
        if (cached == null) {
            long started = System.nanoTime();
            cached = new ArrayList<>();
            DesignationZoneAnimal.collectConnected(cached, root, null, new HashSet<>());
            if (registered && cached.size() <= MAX_REFERENCES) {
                if (cachedReferences + cached.size() > MAX_REFERENCES) { connected.clear(); cachedReferences = 0; }
                connected.put(root, cached);
                cachedReferences += cached.size();
            }
            ApocBRServerTelemetryLite.count("zones.topology.rebuilt", 1L);
            ApocBRServerTelemetryLite.recordPhase("simulation.designationZones.topology", System.nanoTime() - started);
        } else {
            ApocBRServerTelemetryLite.count("zones.topology.cacheHits", 1L);
        }
        if (result == null) result = new ArrayList<>();
        result.addAll(cached);
        return result;
    }
}
