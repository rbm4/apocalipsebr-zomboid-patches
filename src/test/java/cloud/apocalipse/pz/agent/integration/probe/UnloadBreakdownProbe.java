package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.network.ServerMap;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Owner-thread attribution for native unload. Installed only in the integration child. */
public final class UnloadBreakdownProbe {
    public static final String PROPERTY = "pz.it.unloadBreakdown";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    private static final String CHUNK = "zombie.iso.IsoChunk";
    private static final String OBJECT = "zombie.iso.IsoObject";
    private static final String SQUARE = "zombie.iso.IsoGridSquare";
    private static final String CELL = "zombie.network.ServerMap$ServerCell";
    private static final String LOADER = "zombie.network.ServerChunkLoader";
    private static final String CHUNK_ARG = "(Lzombie/iso/IsoChunk;)V";
    private static final String SQUARE_ARG = "(Lzombie/iso/IsoGridSquare;)V";
    private static final String ENTITY_ARG = "(Lzombie/entity/GameEntity;)V";
    private static final String OBJECT_ARG = "(Lzombie/iso/IsoObject;)V";
    private static final String ARRAY = "zombie.entity.util.Array";
    private static final String ARRAY_SEARCH = "(Ljava/lang/Object;Z)Z";

    private enum Stage {
        POSTUPDATE, LOS_SUSPEND, LOS_RESUME, SAVE_COMPLETIONS,
        CELL_UNLOAD, CHUNK_DETACH, VEHICLE_CAPTURE, SAVE_ENQUEUE,
        COLLISION, ZOMBIE_POPULATION, ANIMAL_POPULATION, POPULATION_SAVE, JAVA_PATHFIND, NATIVE_PATHFIND,
        RAIN, WATER, PUDDLES, ROOM, ROOM_EXIT_LOOKUP, ZONE, ADJACENCY, CLEAR_SQUARE, CORPSE_COUNT,
        MOVING_CALLBACK, MOVING_REMOVE_SQUARE, ZOMBIE_CALLBACK, ANIMAL_CALLBACK, VEHICLE_DETACH, CORPSE_CALLBACK, OBJECT_DISPATCH, OBJECT_BASE, ENTITY_OFFLOAD,
        ENTITY_UNREGISTER, ENTITY_REMOVE, BUCKET_UPDATE, BUCKET_MEMBERSHIP,
        PROCESS_OBJECT, STATIC_UPDATER, CONTAINER_ITEMS, PROCESS_ITEMS, ITEM_SOUND,
        AMBIENT_EMITTER, LIGHT, OVERLAY, POWER_PREDICATE, GENERATOR_UNREGISTER, GENERATOR_COUNT, HOT_SAVE,
        ENGINE_ARRAY_SEARCH, BUCKET_ARRAY_SEARCH, OTHER_ARRAY_SEARCH,
        ROOM_SQUARE_LIST, ROOM_EXIT_LIST, ROOM_LIGHT_LIST, STATIC_UPDATER_LIST, ITEM_SOUND_LIST,
        MOVING_LIST, CHUNK_LIST_REMOVE
    }

    private record Target(Stage stage, String type, String method, String descriptor) {
        Target(Stage stage, String type, String method) { this(stage, type, method, "()V"); }
    }

    private static final List<Target> TARGETS = List.of(
        new Target(Stage.LOS_SUSPEND, "zombie.network.ServerLOS", "suspend"),
        new Target(Stage.LOS_RESUME, "zombie.network.ServerLOS", "resume"),
        new Target(Stage.SAVE_COMPLETIONS, LOADER, "updateSaved"),
        new Target(Stage.CHUNK_DETACH, CHUNK, "removeFromWorld"),
        new Target(Stage.VEHICLE_CAPTURE, "zombie.vehicles.VehiclesDB2", "updateVehicle", "(Lzombie/vehicles/BaseVehicle;)V"),
        new Target(Stage.SAVE_ENQUEUE, LOADER, "addSaveUnloadedJob", CHUNK_ARG),
        new Target(Stage.COLLISION, "zombie.MapCollisionData", "removeChunkFromWorld", CHUNK_ARG),
        new Target(Stage.ZOMBIE_POPULATION, "zombie.popman.ZombiePopulationManager", "removeChunkFromWorld", CHUNK_ARG),
        new Target(Stage.ANIMAL_POPULATION, "zombie.characters.animals.AnimalPopulationManager", "removeChunkFromWorld", CHUNK_ARG),
        new Target(Stage.POPULATION_SAVE, "zombie.popman.ZombiePopulationManager", "requestSaveCell", "(II)V"),
        new Target(Stage.JAVA_PATHFIND, "zombie.pathfind.PolygonalMap2", "removeChunkFromWorld", CHUNK_ARG),
        new Target(Stage.NATIVE_PATHFIND, "zombie.pathfind.nativeCode.PathfindNative", "removeChunkFromWorld", CHUNK_ARG),
        new Target(Stage.RAIN, "zombie.iso.objects.RainManager", "RemoveAllOn", SQUARE_ARG),
        new Target(Stage.WATER, SQUARE, "clearWater"),
        new Target(Stage.PUDDLES, SQUARE, "clearPuddles"),
        new Target(Stage.ROOM, "zombie.iso.areas.IsoRoom", "removeSquare", SQUARE_ARG),
        new Target(Stage.ROOM_EXIT_LOOKUP, "zombie.iso.areas.IsoRoom", "getExitAt", "(III)Lzombie/iso/areas/IsoRoomExit;"),
        new Target(Stage.ZONE, "zombie.iso.zones.Zone", "removeSquare", SQUARE_ARG),
        new Target(Stage.ADJACENCY, CHUNK, "disconnectFromAdjacentChunks", SQUARE_ARG),
        new Target(Stage.CLEAR_SQUARE, SQUARE, "softClear"),
        new Target(Stage.CORPSE_COUNT, "zombie.iso.CorpseCount$ChunkData", "removeFromWorld"),
        new Target(Stage.MOVING_CALLBACK, "zombie.iso.IsoMovingObject", "removeFromWorld"),
        new Target(Stage.MOVING_REMOVE_SQUARE, "zombie.iso.IsoMovingObject", "removeFromSquare"),
        new Target(Stage.ZOMBIE_CALLBACK, "zombie.characters.IsoZombie", "removeFromWorld"),
        new Target(Stage.ANIMAL_CALLBACK, "zombie.characters.animals.IsoAnimal", "removeFromWorld"),
        new Target(Stage.CORPSE_CALLBACK, "zombie.iso.objects.IsoDeadBody", "removeFromWorld"),
        new Target(Stage.VEHICLE_DETACH, "zombie.vehicles.BaseVehicle", "removeFromWorld"),
        new Target(Stage.OBJECT_DISPATCH, OBJECT, "removeFromWorldToMeta"),
        new Target(Stage.OBJECT_BASE, OBJECT, "removeFromWorld"),
        new Target(Stage.ENTITY_OFFLOAD, "zombie.entity.GameEntity", "removeFromWorld", "(Z)V"),
        new Target(Stage.ENTITY_UNREGISTER, "zombie.entity.GameEntityManager", "UnregisterEntity", "(Lzombie/entity/GameEntity;Z)V"),
        new Target(Stage.ENTITY_REMOVE, "zombie.entity.EngineEntityManager", "removeEntityInternal", ENTITY_ARG),
        new Target(Stage.BUCKET_UPDATE, "zombie.entity.EntityBucketManager", "updateBucketMembership", ENTITY_ARG),
        new Target(Stage.BUCKET_MEMBERSHIP, "zombie.entity.EntityBucket", "updateMembership", ENTITY_ARG),
        new Target(Stage.PROCESS_OBJECT, "zombie.iso.IsoCell", "addToProcessIsoObjectRemove", OBJECT_ARG),
        new Target(Stage.STATIC_UPDATER, "zombie.iso.IsoCell", "removeFromStaticUpdaterObjectList", OBJECT_ARG),
        new Target(Stage.CONTAINER_ITEMS, "zombie.inventory.ItemContainer", "removeItemsFromProcessItems"),
        new Target(Stage.PROCESS_ITEMS, "zombie.iso.IsoCell", "addToProcessItemsRemove", "(Ljava/util/ArrayList;)V"),
        new Target(Stage.PROCESS_ITEMS, "zombie.iso.IsoCell", "addToProcessItemsRemove", "(Lzombie/inventory/InventoryItem;)V"),
        new Target(Stage.ITEM_SOUND, "zombie.inventory.ItemSoundManager", "removeItem", "(Lzombie/inventory/InventoryItem;)V"),
        new Target(Stage.AMBIENT_EMITTER, CHUNK, "removeObjectAmbientEmitter", OBJECT_ARG),
        new Target(Stage.LIGHT, OBJECT, "removeLightSourceFromWorld"),
        new Target(Stage.OVERLAY, OBJECT, "clearOnOverlay"),
        new Target(Stage.POWER_PREDICATE, OBJECT, "couldBePoweredByGenerator", "()Z"),
        new Target(Stage.GENERATOR_UNREGISTER, "zombie.iso.IsoChunkLevel", "removeObjectPoweredByGenerator", OBJECT_ARG),
        new Target(Stage.GENERATOR_COUNT, "zombie.iso.IsoChunkLevel", "countObjectsPoweredByGenerator", "(ILzombie/iso/IsoObject;)I"),
        new Target(Stage.HOT_SAVE, OBJECT, "flagForHotSave"));

    private record Search(Stage stage, String method, String owner, String operation) {}
    private static final List<Search> SEARCHES = List.of(
        new Search(Stage.ROOM_SQUARE_LIST, "removeSquare", "java/util/ArrayList", "remove"),
        new Search(Stage.ROOM_EXIT_LIST, "removeSquare", "java/util/Vector", "remove"),
        new Search(Stage.ROOM_EXIT_LIST, "removeSquare", "java/util/Vector", "remove"),
        new Search(Stage.ROOM_LIGHT_LIST, "removeSquare", "java/util/ArrayList", "remove"));
    private static final Stage[] STAGES = Stage.values();
    private static final long ORIGIN_NANOS = System.nanoTime();
    private static final Instant ORIGIN_UTC = Instant.now();
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final List<Frame> COMPLETED = new ArrayList<>();
    private static Frame frame;
    private static int frameNumber;
    private static int depth;
    private static int cellDepth;
    private static int[] stack = new int[32];
    private static long[] childWall = new long[32];
    private static int[] searchIndex = new int[32];

    private UnloadBreakdownProbe() {}

    static AgentBuilder configure(AgentBuilder builder) {
        if (!ENABLED) return builder;
        for (Target target : TARGETS) {
            builder = builder.type(named(target.type())).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.withCustomMapping().bind(Phase.class, target.stage().ordinal()).to(Timing.class)
                    .on(named(target.method()).and(hasDescriptor(target.descriptor())))));
        }
        return builder.type(named(CELL)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(CellTiming.class).on(named("Unload").and(hasDescriptor("()V")))))
            .type(named(ARRAY)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(ArraySearch.class).on(named("removeValue").and(hasDescriptor(ARRAY_SEARCH))))
                .visit(Advice.to(ArrayIndex.class).on(named("removeIndex").and(hasDescriptor("(I)Ljava/lang/Object;")))))
            .type(named("zombie.iso.areas.IsoRoom")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("removeSquare", SQUARE_ARG,
                    downstream -> new SearchVisitor(downstream, SEARCHES)))
                .visit(OptimizationVisitor.method("getExitAt", "(III)Lzombie/iso/areas/IsoRoomExit;",
                    downstream -> new VisitCounter(downstream, "java/util/Vector", Stage.ROOM_EXIT_LOOKUP))))
            .type(named("zombie.iso.IsoChunkLevel")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("countObjectsPoweredByGenerator", "(ILzombie/iso/IsoObject;)I",
                    downstream -> new VisitCounter(downstream, "java/util/List", Stage.GENERATOR_COUNT))))
            .type(named("zombie.inventory.ItemSoundManager")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("removeItem", "(Lzombie/inventory/InventoryItem;)V",
                    downstream -> new SearchVisitor(downstream, List.of(
                        new Search(Stage.ITEM_SOUND_LIST, "removeItem", "java/util/ArrayList", "remove"),
                        new Search(Stage.ITEM_SOUND_LIST, "removeItem", "java/util/ArrayList", "contains"))))))
            .type(named("zombie.iso.IsoCell")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("removeFromStaticUpdaterObjectList", OBJECT_ARG,
                    downstream -> new SearchVisitor(downstream, List.of(
                        new Search(Stage.STATIC_UPDATER_LIST, "removeFromStaticUpdaterObjectList", "java/util/ArrayList", "remove"))))))
            .type(named(CHUNK)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("removeFromWorld", "()V",
                    downstream -> new SearchVisitor(downstream, List.of(
                        new Search(Stage.CHUNK_LIST_REMOVE, "removeFromWorld", "java/util/ArrayList", "remove"),
                        new Search(Stage.CHUNK_LIST_REMOVE, "removeFromWorld", "java/util/ArrayList", "remove"),
                        new Search(Stage.MOVING_LIST, "removeFromWorld", "java/util/ArrayList", "contains"))))));
    }

    private static final class SearchVisitor extends MethodVisitor {
        private final List<Search> searches;
        private int index;

        SearchVisitor(MethodVisitor downstream, List<Search> searches) {
            super(ASM9, downstream);
            this.searches = searches;
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == INVOKEVIRTUAL && (owner.equals("java/util/ArrayList") || owner.equals("java/util/Vector"))
                    && desc.equals("(Ljava/lang/Object;)Z") && (name.equals("remove") || name.equals("contains"))) {
                if (index == searches.size() || !searches.get(index).operation().equals(name)
                        || !searches.get(index).owner().equals(owner)) {
                    throw new IllegalStateException("Unexpected unload collection call " + owner + "." + name);
                }
                mv.visitLdcInsn(searches.get(index++).stage().ordinal());
                OptimizationVisitor.callStatic(mv, UnloadBreakdownProbe.class, name + "FromList",
                    "(Ljava/util/Collection;Ljava/lang/Object;I)Z");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack + 1, maxLocals); }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches(searches.getFirst().method() + " unload collections", index, searches.size());
            super.visitEnd();
        }
    }

    public static boolean removeFromList(Collection<?> list, Object value, int stage) {
        long started = enter(stage);
        if (started == 0) return list.remove(value);
        try {
            frame.work[stage] += list.size();
            return list.remove(value);
        } finally {
            exit(started);
        }
    }

    public static boolean containsFromList(Collection<?> list, Object value, int stage) {
        long started = enter(stage);
        if (started == 0) return list.contains(value);
        try {
            frame.work[stage] += list.size();
            return list.contains(value);
        } finally {
            exit(started);
        }
    }

    private static final class VisitCounter extends MethodVisitor {
        private final String collection;
        private final Stage stage;
        private int sites;

        VisitCounter(MethodVisitor downstream, String collection, Stage stage) {
            super(ASM9, downstream);
            this.collection = collection;
            this.stage = stage;
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (owner.equals(collection) && name.equals("get") && desc.equals("(I)Ljava/lang/Object;")) {
                sites++;
                mv.visitLdcInsn(stage.ordinal());
                OptimizationVisitor.callStatic(mv, UnloadBreakdownProbe.class, "visited", "(I)V");
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack + 1, maxLocals); }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches(stage.name() + " candidate read", sites, 1);
            super.visitEnd();
        }
    }

    public static void visited(int stage) {
        if (StreamingProbe.onStreamingOwner() && cellDepth > 0 && stack[depth - 1] == stage) frame.work[stage]++;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Phase {}

    public static class Timing {
        @Advice.OnMethodEnter public static long begin(@Phase int stage) { return enter(stage); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void end(@Advice.Enter long started) { exit(started); }
    }

    public static class CellTiming {
        @Advice.OnMethodEnter public static long begin() { return enterCell(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void end(@Advice.This Object cell, @Advice.Enter long started) { exitCell(cell, started); }
    }

    public static long enterCell() {
        if (!StreamingProbe.onStreamingOwner() || frame == null) return 0;
        cellDepth++;
        return push(Stage.CELL_UNLOAD.ordinal());
    }

    public static void exitCell(Object value, long started) {
        if (started == 0) return;
        long elapsed = exit(started);
        cellDepth--;
        ServerMap.ServerCell cell = (ServerMap.ServerCell) value;
        frame.cells.add(new Cell(cell.wx, cell.wy, started - ORIGIN_NANOS, elapsed));
    }

    public static class ArraySearch {
        @Advice.OnMethodEnter public static long begin() { return enterArraySearch(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void end(@Advice.Enter long started, @Advice.Return boolean found,
                               @Advice.FieldValue("size") int size, @Advice.Thrown Throwable failure) {
            exitArraySearch(started, found, size, failure);
        }
    }

    public static class ArrayIndex {
        @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) int position) { foundIndex(position); }
    }

    public static long enterArraySearch() {
        if (!StreamingProbe.onStreamingOwner() || cellDepth == 0) return 0;
        Stage stage = switch (STAGES[stack[depth - 1]]) {
            case ENTITY_REMOVE -> Stage.ENGINE_ARRAY_SEARCH;
            case BUCKET_MEMBERSHIP -> Stage.BUCKET_ARRAY_SEARCH;
            default -> Stage.OTHER_ARRAY_SEARCH;
        };
        long started = push(stage.ordinal());
        searchIndex[depth - 1] = -1;
        return started;
    }

    public static void foundIndex(int position) {
        if (!StreamingProbe.onStreamingOwner() || cellDepth == 0) return;
        Stage parent = STAGES[stack[depth - 1]];
        if (parent == Stage.ENGINE_ARRAY_SEARCH || parent == Stage.BUCKET_ARRAY_SEARCH
                || parent == Stage.OTHER_ARRAY_SEARCH) searchIndex[depth - 1] = position;
    }

    public static void exitArraySearch(long started, boolean found, int size, Throwable failure) {
        if (started == 0) return;
        int stage = stack[depth - 1];
        int position = searchIndex[depth - 1];
        if (failure == null && (!found || position >= 0)) {
            frame.work[stage] += found ? position + 1L : size;
        }
        exit(started);
    }

    public static long beginPostupdate() {
        if (!ENABLED || !StreamingProbe.onStreamingOwner()) return 0;
        frame = new Frame(StreamingProbe.activeCycle(), ++frameNumber, currentCpu());
        return push(Stage.POSTUPDATE.ordinal());
    }

    public static void endPostupdate(long started) {
        if (started == 0) return;
        exit(started);
        frame.elapsedStart = started - ORIGIN_NANOS;
        long cpu = currentCpu();
        frame.cpu = frame.cpu < 0 || cpu < 0 ? -1 : cpu - frame.cpu;
        if (!frame.cells.isEmpty()) COMPLETED.add(frame);
        frame = null;
    }

    public static long enter(int stage) {
        if (!StreamingProbe.onStreamingOwner() || frame == null) return 0;
        if (cellDepth == 0 && stage != Stage.LOS_SUSPEND.ordinal() && stage != Stage.LOS_RESUME.ordinal()
                && stage != Stage.SAVE_COMPLETIONS.ordinal()) return 0;
        return push(stage);
    }

    private static long push(int stage) {
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, depth * 2);
            childWall = Arrays.copyOf(childWall, depth * 2);
            searchIndex = Arrays.copyOf(searchIndex, depth * 2);
        }
        stack[depth] = stage;
        childWall[depth++] = 0;
        return System.nanoTime();
    }

    public static long exit(long started) {
        if (started == 0) return 0;
        long elapsed = System.nanoTime() - started;
        int stage = stack[--depth];
        frame.calls[stage]++;
        frame.wall[stage] += elapsed;
        frame.self[stage] += elapsed - childWall[depth];
        frame.max[stage] = Math.max(frame.max[stage], elapsed);
        if (depth != 0) childWall[depth - 1] += elapsed;
        return elapsed;
    }

    private static long currentCpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
            ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    static void write(Path directory) throws IOException {
        if (!ENABLED) return;
        var phases = new StringBuilder("cycle,postupdate,phase,calls,wallNanos,selfNanos,maxNanos,searchWork\n");
        var frames = new StringBuilder("cycle,postupdate,startNanos,wallNanos,cpuNanos,cells,chunks\n");
        var cells = new StringBuilder("cycle,postupdate,x,y,startNanos,wallNanos\n");
        for (Frame row : COMPLETED) {
            int chunks = Stage.CHUNK_DETACH.ordinal();
            frames.append(row.cycle).append(',').append(row.number).append(',').append(row.elapsedStart).append(',')
                .append(row.wall[Stage.POSTUPDATE.ordinal()]).append(',').append(row.cpu).append(',')
                .append(row.cells.size()).append(',').append(row.calls[chunks]).append('\n');
            for (Cell cell : row.cells) {
                cells.append(row.cycle).append(',').append(row.number).append(',').append(cell.x()).append(',')
                    .append(cell.y()).append(',').append(cell.nanos()).append(',').append(cell.wall()).append('\n');
            }
            for (Stage stage : STAGES) {
                int i = stage.ordinal();
                if (row.calls[i] == 0) continue;
                phases.append(row.cycle).append(',').append(row.number).append(',').append(stage).append(',')
                    .append(row.calls[i]).append(',').append(row.wall[i]).append(',').append(row.self[i]).append(',')
                    .append(row.max[i]).append(',').append(row.work[i]).append('\n');
            }
        }
        Files.writeString(directory.resolve("unload-clock-utc.txt"), ORIGIN_UTC.toString());
        Files.writeString(directory.resolve("unload-postupdate.csv"), frames);
        Files.writeString(directory.resolve("unload-cell.csv"), cells);
        Files.writeString(directory.resolve("unload-phases.csv"), phases);
    }

    private record Cell(int x, int y, long nanos, long wall) {}

    private static final class Frame {
        final int cycle;
        final int number;
        final List<Cell> cells = new ArrayList<>();
        final long[] calls = new long[STAGES.length];
        final long[] wall = new long[STAGES.length];
        final long[] self = new long[STAGES.length];
        final long[] max = new long[STAGES.length];
        final long[] work = new long[STAGES.length];
        long elapsedStart;
        long cpu;

        Frame(int cycle, int number, long cpu) {
            this.cycle = cycle;
            this.number = number;
            this.cpu = cpu;
        }
    }
}
