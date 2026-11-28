package cloud.apocalipse.pz.agent.features.optimization.building;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashSet;
import java.util.List;
import zombie.buildingRooms.BuildingRoomsEditor;
import zombie.iso.BuildingDef;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoWorld;

/** Keeps the set of affected ID-owner cells within one native commit. */
public final class ScopedRoomCommit {
    private static final ThreadLocal<Scope> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private ScopedRoomCommit() { }

    public static boolean tryApply(BuildingRoomsEditor editor, int x, int y, int z) {
        if (REQUEST.get() != null || ACTIVE.get() != null || editor == null || !editor.isValid()) return false;
        var world = IsoWorld.instance;
        if (world == null || world.getMetaGrid() == null) return false;
        var grid = world.getMetaGrid();
        var room = grid.getRoomAt(x, y, z);
        if (room == null || room.building == null) return false;
        var building = room.building;
        var owner = grid.getCellData(building.x / 256, building.y / 256);
        if (owner == null || owner.buildingByMetaId.get(building.metaId) != building) return false;

        var request = new Scope(editor);
        request.owners.add(owner);
        REQUEST.set(request);
        try {
            editor.applyChanges(false);
            return true;
        } finally {
            REQUEST.remove();
        }
    }

    public static Scope enter(Object editor, boolean loading, List<?> oldBuildings) {
        Scope previous = ACTIVE.get();
        Scope request = REQUEST.get();
        if (request != null && request.editor == editor && !loading) {
            var grid = IsoWorld.instance.getMetaGrid();
            for (Object old : oldBuildings) {
                var building = (BuildingDef) old;
                var cell = grid.getCellData(building.x / 256, building.y / 256);
                if (cell != null) request.owners.add(cell);
            }
            REQUEST.remove();
            ACTIVE.set(request);
        } else {
            // A nested direct engine commit keeps its full native passes.
            ACTIVE.remove();
        }
        return previous;
    }

    public static void exit(Scope previous) {
        if (previous != null) ACTIVE.set(previous);
        else ACTIVE.remove();
    }

    public static void trackOwner(IsoMetaCell cell) {
        Scope scope = ACTIVE.get();
        if (scope != null && cell != null) scope.owners.add(cell);
    }

    public static void check(BuildingRoomsEditor editor) {
        Scope scope = ACTIVE.get();
        if (scope == null || scope.editor != editor) {
            editor.checkBuildingAndRoomIDs();
            return;
        }
        for (IsoMetaCell cell : scope.owners) editor.checkBuildingAndRoomIDs(cell);
    }

    public static void rebuild(BuildingRoomsEditor editor) {
        Scope scope = ACTIVE.get();
        try {
            if (scope == null || scope.editor != editor) NativeReindex.WORLD.invokeExact(editor);
            else {
                for (IsoMetaCell cell : scope.owners) NativeReindex.CELL.invokeExact(editor, cell);
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Native room reindex failed", failure);
        }
    }

    public static final class Scope {
        private final BuildingRoomsEditor editor;
        private final LinkedHashSet<IsoMetaCell> owners = new LinkedHashSet<>();

        private Scope(BuildingRoomsEditor editor) { this.editor = editor; }
    }

    private static final class NativeReindex {
        private static final MethodHandle WORLD = find(MethodType.methodType(void.class));
        private static final MethodHandle CELL = find(MethodType.methodType(void.class, IsoMetaCell.class));

        private static MethodHandle find(MethodType type) {
            try {
                return MethodHandles.privateLookupIn(BuildingRoomsEditor.class, MethodHandles.lookup())
                    .findVirtual(BuildingRoomsEditor.class, "recalculateBuildingAndRoomIDs", type);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Original room reindex signature changed", failure);
            }
        }
    }
}
