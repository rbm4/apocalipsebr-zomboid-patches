package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.building.RoomIdValidationModule;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaThread;
import zombie.Lua.LuaManager;
import zombie.buildingRooms.BuildingRoomsEditor;
import zombie.iso.BuildingDef;
import zombie.iso.BuildingID;
import zombie.iso.IsoLot;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Runs disposable room edits on the native server thread after readiness. */
public final class RoomRegistrationProbe {
    public static final String PROPERTY = "pz.it.roomRegistration";
    public static final String EDITOR = "zombie.buildingRooms.BuildingRoomsEditor";
    public static final int CYCLES = 7;
    public static final String CSV = "room-registration.csv";
    public static final String REQUEST = "room-registration-request";
    public static final String COMPLETE = "room-registration-complete";
    public static final String ERROR = "room-registration-error.txt";
    private static final int LEVEL = -1;
    private static final String MAIN = "apocbr-room-integration-main";
    private static final String EAST = "apocbr-room-integration-east";
    private static final String SIBLING = "apocbr-room-integration-sibling";
    private static final ThreadLocal<Sample> ACTIVE = new ThreadLocal<>();
    private static Path directory;
    private static boolean requested;

    private RoomRegistrationProbe() { }

    static void install(Path output, Instrumentation instrumentation) {
        directory = output;
        new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(named("zombie.network.ServerMap"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(Tick.class)
                .on(named("preupdate").and(hasDescriptor("()V")))))
            .type(named(EDITOR))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(Timing.class)
                .on(hasDescriptor("()V").and(named("checkBuildingAndRoomIDs")
                    .or(named("recalculateBuildingAndRoomIDs")).or(named("updateSquares")))))
                .visit(Advice.to(CellTiming.class).on(hasDescriptor("(Lzombie/iso/IsoMetaCell;)V")
                    .and(named("checkBuildingAndRoomIDs").or(named("recalculateBuildingAndRoomIDs"))))))
            .type(named("zombie.buildingRooms.BREBuilding"))
            .transform((dynamic, type, loader, module, domain) -> dynamic.visit(Advice.to(Timing.class)
                .on(named("removeFromWorld").or(named("applyChanges")))))
            .installOn(instrumentation);
    }

    public static final class Tick {
        @Advice.OnMethodExit
        public static void exit() { RoomRegistrationProbe.onTick(); }
    }

    public static final class Timing {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Origin("#m") String method) {
            return RoomRegistrationProbe.startPhase(method);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Origin("#m") String method, @Advice.Enter long start) {
            if (start != 0) RoomRegistrationProbe.finishPhase(method, start);
        }
    }

    public static final class CellTiming {
        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin("#m") String method, @Advice.Argument(0) IsoMetaCell cell) {
            RoomRegistrationProbe.recordCellPass(method, cell);
        }
    }

    public static void recordCellPass(String method, IsoMetaCell cell) {
        var sample = ACTIVE.get();
        if (sample == null || cell == null || sample.global) return;
        if (method.equals("checkBuildingAndRoomIDs")) sample.localChecks++;
        else sample.localRebuilds++;
        sample.cells.add(cell);
    }

    public static long startPhase(String method) {
        var sample = ACTIVE.get();
        if (sample == null) return 0;
        if (method.equals("checkBuildingAndRoomIDs") || method.equals("recalculateBuildingAndRoomIDs")) {
            sample.global = true;
        }
        return System.nanoTime();
    }

    public static void finishPhase(String method, long start) {
        long elapsed = System.nanoTime() - start;
        var sample = ACTIVE.get();
        switch (method) {
            case "checkBuildingAndRoomIDs" -> {
                sample.global = false;
                sample.checks++;
                sample.checkNs += elapsed;
            }
            case "recalculateBuildingAndRoomIDs" -> {
                sample.global = false;
                sample.rebuilds++;
                sample.rebuildNs += elapsed;
            }
            case "removeFromWorld" -> {
                sample.removals++;
                sample.removeNs += elapsed;
            }
            case "applyChanges" -> sample.insertNs += elapsed;
            case "updateSquares" -> sample.updateNs += elapsed;
            default -> throw new IllegalArgumentException(method);
        }
    }

    public static void onTick() {
        if (requested || !Files.isRegularFile(directory.resolve(REQUEST))) return;
        requested = true;
        try {
            runScenario();
            Files.createFile(directory.resolve(COMPLETE));
        } catch (Throwable failure) {
            try (var writer = new PrintWriter(Files.newBufferedWriter(directory.resolve(ERROR)))) {
                failure.printStackTrace(writer);
            } catch (Exception writing) {
                throw new IllegalStateException("Could not write room fixture failure", writing);
            }
        }
    }

    private record Site(int x, int y, IsoMetaCell cell, IsoMetaCell east) { }

    private static Site findSite(IsoMetaGrid grid, boolean border) {
        var nearby = new ArrayList<BuildingDef>();
        var headers = IsoLot.InfoHeaders.values().stream()
            .sorted(Comparator.comparingInt((zombie.iso.LotHeader h) -> h.cellX).thenComparingInt(h -> h.cellY))
            .toList();
        for (var header : headers) {
            var cell = grid.getCellData(header.cellX, header.cellY);
            if (cell == null) continue;
            var east = border ? grid.getCellData(header.cellX + 1, header.cellY) : cell;
            if (east == null) continue;
            int x = header.cellX * 256 + (border ? 254 : 128);
            int y = header.cellY * 256 + 128;
            nearby.clear();
            grid.getBuildingsIntersecting(x - 16, y - 16, 32, 32, true, nearby);
            if (nearby.isEmpty()) return new Site(x, y, cell, east);
        }
        throw new IllegalStateException("No empty fixture area with an original lot header");
    }

    private static void runScenario() throws Exception {
        // Test compilation also sees the minimal IsoWorld double.
        var grid = (IsoMetaGrid) IsoWorld.class.getMethod("getMetaGrid").invoke(IsoWorld.instance);
        verifyLookupFallback();
        var editor = BuildingRoomsEditor.getInstance();
        var lua = new LuaCommit();
        var out = new StringBuilder("cycle,operation,applyNs,globalCheckNs,globalChecks,"
            + "globalRebuildNs,globalRebuilds,removeNs,removals,insertNs,updateSquaresNs,"
            + "scenario,scoped,localChecks,localRebuilds,localCells\n");
        runGrowth(grid, findSite(grid, false), editor, lua, out, "interior");
        var border = findSite(grid, true);
        runGrowth(grid, border, editor, lua, out, "border");
        runOwnerMove(grid, border, editor, lua, out);
        Files.writeString(directory.resolve(CSV), out);
    }

    private static void runGrowth(IsoMetaGrid grid, Site site, BuildingRoomsEditor editor, LuaCommit lua,
            StringBuilder out, String scenario) {
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            int x = site.x(), y = site.y();
            try {
                BuildingRoomsEditor.Reset();
                addRoom(editor, MAIN, x, y, 2);
                addRoom(editor, SIBLING, x - 8, y, 1);
                addRoom(editor, EAST, x + 10, y, 1);
                apply(editor, null, site, out, cycle, scenario, "create");
                var original = requireRoom(grid, site, x, MAIN);
                var sibling = requireRoom(grid, site.cell(), x - 8, y, SIBLING);
                var eastRoom = requireRoom(grid, site.east(), x + 10, y, EAST);
                long eastId = eastRoom.id;
                var eastLive = eastRoom.getIsoRoom();
                var liveSibling = sibling.getIsoRoom();
                require(liveSibling != null, "Sibling runtime room missing");
                require(grid.getRoomAt(x + 2, y, LEVEL) == null, "Growth tile was occupied before edit");
                long buildingMetaId = original.building.metaId;

                BuildingRoomsEditor.Reset();
                addRoom(editor, MAIN, x, y, 3);
                apply(editor, lua, site, out, cycle, scenario, "grow");
                var grown = requireRoom(grid, site, x, MAIN);
                require(grown != original && grown.building.metaId == buildingMetaId,
                    "Main building was not replaced under the original meta ID");
                require(!site.cell().roomList.contains(original), "Original room is still registered");
                require(requireRoom(grid, site, x + 2, MAIN) == grown, "Growth tile has a different room");
                require(grid.getRoomAt(x + 3, y, LEVEL) == null, "Growth covered an extra tile");
                require(requireRoom(grid, site.cell(), x - 8, y, SIBLING) == sibling, "Sibling definition was replaced");
                require(sibling.getIsoRoom() == liveSibling && site.cell().isoRooms.get(sibling.id) == liveSibling,
                    "Sibling live room lookup was not preserved after ID rebuild");
                if (site.cell() != site.east()) {
                    require(!site.east().roomList.contains(grown) && grid.getRoomDefByID(grown.id) == grown,
                        "Cross-border spatial coverage changed room ID ownership");
                    require(requireRoom(grid, site.east(), x + 10, y, EAST) == eastRoom
                        && eastRoom.id == eastId && eastRoom.getIsoRoom() == eastLive,
                        "Unrelated room in the neighboring cell changed");
                }
            } finally {
                cleanup(editor, x, y);
            }
            require(grid.getRoomAt(x, y, LEVEL) == null && grid.getRoomAt(x + 2, y, LEVEL) == null
                && grid.getRoomAt(x - 8, y, LEVEL) == null && grid.getRoomAt(x + 10, y, LEVEL) == null,
                "Fixture rooms were not removed");
        }
    }

    private static void runOwnerMove(IsoMetaGrid grid, Site site, BuildingRoomsEditor editor, LuaCommit lua,
            StringBuilder out) {
        int x = site.x(), y = site.y();
        try {
            BuildingRoomsEditor.Reset();
            addRoom(editor, MAIN, x, y, 1);
            addRoom(editor, SIBLING, x - 8, y, 1);
            editor.applyChanges(false);
            var oldRoom = requireRoom(grid, site, x, MAIN);
            editor.init(x, y);
            while (editor.getBuildingCount() > 0) editor.removeBuilding(editor.getBuildingByIndex(0));
            addRoom(editor, MAIN, x + 2, y, 1);
            apply(editor, lua, site, out, 0, "move-owner", "grow");
            var moved = requireRoom(grid, site.east(), x + 2, y, MAIN);
            require(!site.cell().roomList.contains(oldRoom) && !site.cell().roomList.contains(moved)
                && grid.getRoomAt(x, y, LEVEL) == null, "Original owner retained the moved room");
        } finally {
            cleanup(editor, x, y);
        }
    }

    private static void cleanup(BuildingRoomsEditor editor, int x, int y) {
        BuildingRoomsEditor.Reset();
        editor.init(x, y);
        while (editor.getBuildingCount() > 0) editor.removeBuilding(editor.getBuildingByIndex(0));
        editor.applyChanges(false);
        BuildingRoomsEditor.Reset();
    }

    private static void verifyLookupFallback() {
        var rooms = new ArrayList<RoomDef>();
        var first = new RoomDef(RoomID.makeID(12, 34, 0), "first");
        var second = new RoomDef(first.id, "second");
        rooms.add(first);
        rooms.add(second);
        require(RoomIdValidationModule.roomIndex(rooms, second) == 1, "Stale index lost a room");
        second.id = RoomID.makeID(12, 34, 8);
        require(RoomIdValidationModule.roomIndex(rooms, second) == 1, "Out-of-bounds index lost a room");
        require(RoomIdValidationModule.roomIndex(rooms, new RoomDef(first.id, "missing")) == -1,
            "Missing room was incorrectly matched");
    }

    private static RoomDef requireRoom(IsoMetaGrid grid, Site site, int x, String name) {
        return requireRoom(grid, site.cell(), x, site.y(), name);
    }

    private static RoomDef requireRoom(IsoMetaGrid grid, IsoMetaCell cell, int x, int y, String name) {
        RoomDef room = grid.getRoomAt(x, y, LEVEL);
        require(room != null && name.equals(room.getName()), "Missing room " + name + " at x=" + x);
        int roomIndex = RoomID.getIndex(room.id);
        int buildingIndex = BuildingID.getIndex(room.building.id);
        require(cell.roomList.get(roomIndex) == room && cell.rooms.get(room.id) == room
            && cell.roomByMetaId.get(room.metaId) == room, "Room lookup disagrees with its IDs");
        require(cell.buildings.get(buildingIndex) == room.building
            && cell.buildingByMetaId.get(room.building.metaId) == room.building,
            "Building lookup disagrees with its IDs");
        return room;
    }

    private static void addRoom(BuildingRoomsEditor editor, String name, int x, int y, int width) {
        var room = editor.createBuilding().createRoom(LEVEL);
        room.setName(name);
        room.addRectangle(x, y, width, 1);
    }

    private static void apply(BuildingRoomsEditor editor, LuaCommit lua, Site site, StringBuilder out,
            int cycle, String scenario, String operation) {
        require(editor.isValid(), "Editor rejected prepared fixture");
        var sample = new Sample();
        ACTIVE.set(sample);
        long start = System.nanoTime();
        long elapsed;
        boolean scoped = false;
        try {
            if (lua == null) editor.applyChanges(false);
            else scoped = lua.apply(editor, site.x(), site.y());
        } finally {
            elapsed = System.nanoTime() - start;
            ACTIVE.remove();
        }
        out.append(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%b,%d,%d,%d%n",
            cycle, operation, elapsed, sample.checkNs, sample.checks, sample.rebuildNs, sample.rebuilds,
            sample.removeNs, sample.removals, sample.insertNs, sample.updateNs, scenario, scoped,
            sample.localChecks, sample.localRebuilds, sample.cells.size()));
    }

    private static final class LuaCommit {
        private final KahluaThread thread;
        private final Object closure;

        private LuaCommit() throws Exception {
            // The compile-time LuaManager double has no game VM field.
            thread = (KahluaThread) LuaManager.class.getField("thread").get(null);
            closure = LuaCompiler.loadstring("""
                local editor, x, y, z = ...
                local scoped = ApocBRRoomEdits and ApocBRRoomEdits.tryApply(editor, x, y, z) or false
                if not scoped then editor:applyChanges(false) end
                return scoped
                """, "room-registration-fixture", LuaManager.env);
        }

        private boolean apply(BuildingRoomsEditor editor, int x, int y) {
            Object[] result = thread.pcall(closure, new Object[] {editor, (double) x, (double) y, (double) LEVEL});
            require(Boolean.TRUE.equals(result[0]), "Lua editor call failed: " + java.util.Arrays.toString(result));
            return Boolean.TRUE.equals(result[1]);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Sample {
        int checks, rebuilds, removals, localChecks, localRebuilds;
        boolean global;
        final java.util.Set<IsoMetaCell> cells = new java.util.HashSet<>();
        long checkNs, rebuildNs, removeNs, insertNs, updateNs;
    }
}
