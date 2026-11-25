package cloud.apocalipse.pz.agent.features.optimization.streaming;

import gnu.trove.set.hash.TLongHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import zombie.characters.IsoGameCharacter.Location;
import zombie.core.random.RandStandard;
import zombie.iso.BuildingDef;
import zombie.iso.IsoChunk;
import zombie.iso.WorldReuserThread;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid;
import zombie.iso.areas.isoregion.regions.IsoRegionManager;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

final class BuildingReconciliationOriginalMain {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]);
        for (Class<?> type : List.of(WorldRegionToMetaGrid.class, DataRoot.class, IsoWorldRegion.class, IsoChunk.class)) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(Files.isSameFile(original, origin), type.getName() + " loaded from " + origin);
        }
        boolean enabled = Boolean.parseBoolean(args[1]);
        RandStandard.INSTANCE.init();
        var root = new DataRoot();
        field(IsoRegions.class, "dataRoot").set(null, root);
        var reconciler = (WorldRegionToMetaGrid) field(DataRoot.class, "wrtmg").get(null);
        var processed = (Collection<?>) field(WorldRegionToMetaGrid.class, "allWorldRegions").get(reconciler);
        Object dirty = field(WorldRegionToMetaGrid.class, "dirtyChunks").get(reconciler);
        check(processed.getClass() == (enabled ? LinkedHashSet.class : ArrayList.class), "unexpected region collection");
        check(dirty.getClass() == (enabled ? TLongHashSet.class : ArrayList.class), "unexpected dirty collection");

        checkTraversal(reconciler, enabled);
        checkMembership(reconciler, processed);
        checkDirtyChunks(reconciler, root, dirty, processed);
        System.out.println("ORIGINAL_BUILDING_RECONCILIATION_PASS");
    }

    private static void checkTraversal(WorldRegionToMetaGrid reconciler, boolean enabled) throws Exception {
        Method visit = method("getConnectedRegions", IsoWorldRegion.class, ArrayList.class);
        var first = region(true, 2);
        var left = region(true, 1);
        var right = region(true, 2);
        var shared = region(true, 2);
        var open = region(false, 2);
        var unroofed = region(true, 0);
        first.getNeighbors().addAll(List.of(left, right, open, unroofed));
        left.getNeighbors().add(shared);
        shared.getNeighbors().add(first);
        right.getNeighbors().add(shared);
        var visited = new ArrayList<IsoWorldRegion>();
        visit.invoke(reconciler, first, visited);
        check(visited.equals(List.of(first, left, shared, right)), "depth-first order or eligibility changed");
        visited.clear();
        visited.add(left);
        visit.invoke(reconciler, first, visited);
        check(visited.equals(List.of(left, first, right, shared)), "seeded visited list was ignored");

        if (enabled) {
            var chain = region(true, 2);
            var tail = chain;
            for (int i = 1; i < 12_000; i++) {
                var next = region(true, 2);
                tail.getNeighbors().add(next);
                tail = next;
            }
            visited.clear();
            visit.invoke(reconciler, chain, visited);
            check(visited.size() == 12_000 && visited.getLast() == tail, "deep traversal was incomplete");
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkMembership(WorldRegionToMetaGrid reconciler, Collection<?> processed) throws Exception {
        Method assign = method("assignBuildingToRegions", ArrayList.class, BuildingDef.class);
        var building = new BuildingDef();
        var byBuilding = (Map<BuildingDef, ArrayList<IsoWorldRegion>>)
            field(WorldRegionToMetaGrid.class, "buildingToRegions").get(reconciler);
        var assigned = new ArrayList<IsoWorldRegion>();
        byBuilding.put(building, assigned);
        var left = region(true, 2);
        var right = region(true, 2);
        assign.invoke(reconciler, new ArrayList<>(List.of(left, right, left)), building);
        check(new ArrayList<>(processed).equals(List.of(left, right)), "duplicate or unordered processed regions");
        check(assigned.equals(List.of(left, right, left)), "building assignment list changed");
        assign.invoke(reconciler, new ArrayList<>(List.of(left)), null);
        check(left.getBuildingDef() == null, "vanilla disassociation did not run");
        assign.invoke(reconciler, new ArrayList<>(List.of(left)), building);
        check(new ArrayList<>(processed).equals(List.of(right, left)), "removal and reinsertion order changed");
    }

    @SuppressWarnings("unchecked")
    private static void checkDirtyChunks(WorldRegionToMetaGrid reconciler, DataRoot root, Object dirty,
            Collection<?> processed) throws Exception {
        Method mark = method("markChunkDirty", int.class, int.class);
        Method query = method("chunkIsDirty", IsoChunk.class);
        mark.invoke(reconciler, -1, 1);
        mark.invoke(reconciler, -1, 1);
        mark.invoke(reconciler, 1, -1);
        mark.invoke(reconciler, 0, 0);
        mark.invoke(reconciler, 16_777_216, 2);
        check(dirtySize(dirty) == 4, "dirty marks were lost or duplicated");
        var chunk = new IsoChunk((WorldReuserThread) null);
        chunk.wx = -1;
        chunk.wy = 1;
        check((boolean) query.invoke(reconciler, chunk), "signed chunk not found");
        chunk.wx = 16_777_217;
        chunk.wy = 2;
        check((boolean) query.invoke(reconciler, chunk), "vanilla float coordinate rounding changed");
        chunk.wy = -2;
        check(!(boolean) query.invoke(reconciler, chunk), "unmarked chunk found");

        var changed = (ArrayList<Location>) field(DataRoot.class, "changedCells").get(root);
        root.clientProcessBuildings();
        check(dirtySize(dirty) == 4, "empty reconciliation ran a dirty reset");
        changed.add(null);
        try {
            root.clientProcessBuildings();
            throw new AssertionError("vanilla reconciliation exception was swallowed");
        } catch (NullPointerException expected) {
            check(dirtySize(dirty) == 0, "nonempty reconciliation skipped the dirty reset");
            check(changed.size() == 1, "failed reconciliation unexpectedly consumed changed cells");
        }
        changed.set(0, new Location(-42, -15, 0));
        mark.invoke(reconciler, -1, 1);
        root.clientProcessBuildings();
        check(changed.isEmpty() && processed.isEmpty() && dirtySize(dirty) == 0,
            "successful vanilla reconciliation did not clear its working state");
    }

    private static int dirtySize(Object dirty) {
        return dirty instanceof TLongHashSet set ? set.size() : ((List<?>) dirty).size();
    }

    private static IsoWorldRegion region(boolean enclosed, int roof) throws ReflectiveOperationException {
        var ctor = IsoWorldRegion.class.getDeclaredConstructor(IsoRegionManager.class);
        ctor.setAccessible(true);
        var region = ctor.newInstance((IsoRegionManager) null);
        field(IsoWorldRegion.class, "enclosed").setBoolean(region, enclosed);
        field(IsoWorldRegion.class, "squareSize").setInt(region, 2);
        field(IsoWorldRegion.class, "roofCnt").setInt(region, roof);
        return region;
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        var method = WorldRegionToMetaGrid.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
