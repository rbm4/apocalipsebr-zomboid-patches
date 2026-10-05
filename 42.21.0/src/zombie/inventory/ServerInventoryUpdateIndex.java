package zombie.inventory;

import java.util.ArrayList;
import zombie.ApocBRServerTelemetryLite;
import zombie.inventory.types.InventoryContainer;
import zombie.interfaces.IUpdater;
import zombie.util.list.MutationTrackedArrayList;

/** Per-container sparse routes preserve the vanilla child-before-parent traversal. */
public final class ServerInventoryUpdateIndex {
    public static final class Items extends MutationTrackedArrayList<InventoryItem> {
        private long indexedRevision = Long.MIN_VALUE;
        private int[] routes = new int[0];
        int[] routes() {
            if (indexedRevision != mutationVersion()) {
                int[] next = new int[size()];
                int count = 0;
                for (int i = 0; i < size(); i++) {
                    InventoryItem item = get(i);
                    if (item instanceof InventoryContainer || item instanceof IUpdater) next[count++] = i;
                }
                routes = java.util.Arrays.copyOf(next, count);
                indexedRevision = mutationVersion();
                ApocBRServerTelemetryLite.count("players.inventory.indexRebuilt", 1);
                ApocBRServerTelemetryLite.count("players.inventory.discoveryEntries", size());
            }
            return routes;
        }
    }
    public static void update(ItemContainer container) {
        ArrayList<InventoryItem> items = container.items;
        if (!(items instanceof Items indexed)) {
            // Keep externally supplied/public-field lists and their identity unchanged.
            scan(container, 0);
            return;
        }
        int[] routes = indexed.routes();
        long revision = indexed.mutationVersion();
        for (int index : routes) {
            InventoryItem item = items.get(index);
            update(item);
            if (container.items != items || indexed.mutationVersion() != revision) {
                // Vanilla increments its current index even after a callback removes it.
                scan(container, index + 1);
                ApocBRServerTelemetryLite.count("players.inventory.mutationFallback", 1);
                return;
            }
        }
    }
    private static void scan(ItemContainer container, int start) {
        for (int i = start; i < container.items.size(); i++) {
            ApocBRServerTelemetryLite.count("players.inventory.fallbackEntries", 1);
            update(container.items.get(i));
        }
    }
    private static void update(InventoryItem item) {
        ApocBRServerTelemetryLite.count("players.inventory.routesVisited", 1);
        if (item instanceof InventoryContainer nested) update(nested.getInventory());
        if (item instanceof IUpdater) {
            item.update();
            ApocBRServerTelemetryLite.count("players.inventory.updateAttempts", 1);
        }
    }
}
