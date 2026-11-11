package zombie.network;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-owned fixed-power rectangles, registered by trusted server Lua. */
public final class ApocBRFixedPower {
    private static final Map<String, Region> regions = new LinkedHashMap<>();
    private static volatile Region[] snapshot = new Region[0];
    private ApocBRFixedPower() {}
    private static final class Region {
        final int x1, y1, x2, y2, z1, z2;
        Region(int x1, int y1, int x2, int y2, int z1, int z2) {
            this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
            this.y1 = Math.min(y1, y2); this.y2 = Math.max(y1, y2);
            this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        }
        boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }
    /** Replace a named region atomically; inclusive bounds survive chunk unloads. */
    public static synchronized boolean setRegion(String id, int x1, int y1, int x2, int y2, int z1, int z2) {
        if (!GameServer.server || id == null || id.isEmpty() || id.length() > 128
            || x1 < 0 || x2 < 0 || y1 < 0 || y2 < 0 || x1 > 1000000 || x2 > 1000000
            || y1 > 1000000 || y2 > 1000000 || z1 < -32 || z2 < -32 || z1 > 31 || z2 > 31
            || (!regions.containsKey(id) && regions.size() >= 256)) return false;
        regions.put(id, new Region(x1, y1, x2, y2, z1, z2));
        snapshot = regions.values().toArray(new Region[0]);
        return true;
    }
    public static synchronized boolean removeRegion(String id) {
        if (!GameServer.server || id == null || regions.remove(id) == null) return false;
        snapshot = regions.values().toArray(new Region[0]);
        return true;
    }
    public static boolean contains(int x, int y, int z) {
        if (!GameServer.server) return false;
        for (Region region : snapshot) if (region.contains(x, y, z)) return true;
        return false;
    }
}
