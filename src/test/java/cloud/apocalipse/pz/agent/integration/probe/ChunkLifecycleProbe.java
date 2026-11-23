package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Measures calls at the original chunk lifecycle sites, excluding other caller paths. */
public final class ChunkLifecycleProbe extends MethodVisitor {
    public static final String PROFILE_PROPERTY = "pz.it.chunkLifecycle";
    public static final String COMPARE_PROPERTY = "pz.it.chunkLifecycleComparison";
    private static final String CHUNK = "zombie/iso/IsoChunk";
    private static final String SQUARE = "zombie/iso/IsoGridSquare";
    private static final String CHUNK_ARG = "(L" + CHUNK + ";)V";
    private static final String SQUARE_ARG = "(L" + SQUARE + ";)V";
    private static final String BUILDINGS = "(IIIIZLjava/util/ArrayList;)V";
    private record Site(String phase, boolean load, String owner, String method, String descriptor, int anchors) {
        Site(String phase, boolean load, String owner, String method, String descriptor) {
            this(phase, load, owner, method, descriptor, 1);
        }
    }
    private static final List<Site> SITES = List.of(
        new Site("load.animalsMeta", true, "zombie/iso/IsoMetaCell", "checkAnimalZonesGenerated", "(II)V"),
        new Site("load.vehicles", true, CHUNK, "AddVehicles", "()V"),
        new Site("load.zoneStory", true, CHUNK, "AddZombieZoneStory", "()V"),
        new Site("load.ranchAnimals", true, CHUNK, "AddRanchAnimals", "()V"),
        new Site("load.grass", true, CHUNK, "CheckGrassRegrowth", "()V"),
        new Site("load.update", true, CHUNK, "update", "()V"),
        new Site("load.objects", true, "zombie/iso/IsoObject", "addToWorld", "()V"),
        new Site("load.staticMoving", true, "zombie/iso/IsoMovingObject", "addToWorld", "()V"),
        new Site("load.mapNew", true, "zombie/Lua/MapObjects", "newGridSquare", SQUARE_ARG),
        new Site("load.mapObjects", true, "zombie/Lua/MapObjects", "loadGridSquare", SQUARE_ARG),
        new Site("load.luaEvents", true, "zombie/Lua/LuaEventManager", "triggerEvent", "(Ljava/lang/String;Ljava/lang/Object;)V", 2),
        new Site("load.erosionSquare", true, "zombie/erosion/ErosionMain", "LoadGridsquare", SQUARE_ARG),
        new Site("load.erosionChunk", true, "zombie/erosion/ErosionMain", "ChunkLoaded", CHUNK_ARG),
        new Site("load.collision", true, "zombie/MapCollisionData", "addChunkToWorld", CHUNK_ARG),
        new Site("load.zombiePopulation", true, "zombie/popman/ZombiePopulationManager", "addChunkToWorld", CHUNK_ARG),
        new Site("load.animalPopulation", true, "zombie/characters/animals/AnimalPopulationManager", "addChunkToWorld", CHUNK_ARG),
        new Site("load.pathfind", true, "zombie/pathfind/PolygonalMap2", "addChunkToWorld", CHUNK_ARG),
        new Site("load.buildings", true, "zombie/iso/IsoMetaGrid", "getBuildingsIntersecting", BUILDINGS),
        new Site("load.randomizeBuildings", true, CHUNK, "randomizeBuildingsEtc", "(Ljava/util/ArrayList;)V"),
        new Site("load.overlays", true, CHUNK, "loadGridSquareIfNeeded", SQUARE_ARG),
        new Site("load.adjacent", true, CHUNK, "checkAdjacentChunks", "()V"),
        new Site("unload.collision", false, "zombie/MapCollisionData", "removeChunkFromWorld", CHUNK_ARG),
        new Site("unload.zombiePopulation", false, "zombie/popman/ZombiePopulationManager", "removeChunkFromWorld", CHUNK_ARG),
        new Site("unload.animalPopulation", false, "zombie/characters/animals/AnimalPopulationManager", "removeChunkFromWorld", CHUNK_ARG),
        new Site("unload.populationSaveRequest", false, "zombie/popman/ZombiePopulationManager", "requestSaveCell", "(II)V"),
        new Site("unload.pathfind", false, "zombie/pathfind/PolygonalMap2", "removeChunkFromWorld", CHUNK_ARG),
        new Site("unload.rain", false, "zombie/iso/objects/RainManager", "RemoveAllOn", SQUARE_ARG),
        new Site("unload.water", false, SQUARE, "clearWater", "()V"),
        new Site("unload.puddles", false, SQUARE, "clearPuddles", "()V"),
        new Site("unload.room", false, "zombie/iso/areas/IsoRoom", "removeSquare", SQUARE_ARG),
        new Site("unload.zone", false, "zombie/iso/zones/Zone", "removeSquare", SQUARE_ARG),
        new Site("unload.moving", false, "zombie/iso/IsoMovingObject", "removeFromWorld", "()V", 2),
        new Site("unload.objects", false, "zombie/iso/IsoObject", "removeFromWorldToMeta", "()V"),
        new Site("unload.adjacent", false, CHUNK, "disconnectFromAdjacentChunks", SQUARE_ARG),
        new Site("unload.softClear", false, SQUARE, "softClear", "()V"));

    private static final long[] calls = new long[SITES.size()];
    private static final long[] totals = new long[SITES.size()];
    private static final long[] maxima = new long[SITES.size()];
    private static long started;
    private final int[] matches = new int[SITES.size()];
    private final boolean load;
    private final boolean comparison;

    private ChunkLifecycleProbe(MethodVisitor downstream, boolean load, boolean comparison) {
        super(ASM9, downstream);
        this.load = load;
        this.comparison = comparison;
    }

    static AgentBuilder configure(AgentBuilder builder, boolean comparison) {
        builder = builder.type(named(CHUNK.replace('/', '.'))).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("doLoadGridsquare", "()V",
                    visitor -> new ChunkLifecycleProbe(visitor, true, comparison)))
                .visit(OptimizationVisitor.method("removeFromWorld", "()V",
                    visitor -> new ChunkLifecycleProbe(visitor, false, comparison))));
        return comparison ? ChunkLifecycleComparison.configure(builder) : builder;
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
        for (int index = 0; index < SITES.size(); index++) {
            Site site = SITES.get(index);
            if (site.load != load || !site.owner.equals(owner) || !site.method.equals(method) || !site.descriptor.equals(descriptor)) continue;
            matches[index]++;
            if (comparison && site.phase.equals("unload.adjacent")) {
                mv.visitInsn(DUP);
                OptimizationVisitor.callStatic(mv, ChunkLifecycleComparison.class, "observeUnlink", "(Ljava/lang/Object;)V");
            }
            OptimizationVisitor.callStatic(mv, ChunkLifecycleProbe.class, "begin", "()V");
            super.visitMethodInsn(opcode, owner, method, descriptor, itf);
            mv.visitLdcInsn(index);
            OptimizationVisitor.callStatic(mv, ChunkLifecycleProbe.class, "end", "(I)V");
            return;
        }
        super.visitMethodInsn(opcode, owner, method, descriptor, itf);
    }

    @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(maxStack + 1, maxLocals); }

    @Override public void visitEnd() {
        for (int index = 0; index < SITES.size(); index++) {
            Site site = SITES.get(index);
            if (site.load == load) OptimizationVisitor.requireMatches(site.phase, matches[index], site.anchors);
        }
        super.visitEnd();
    }

    public static void begin() {
        if (StreamingProbe.onStreamingOwner()) started = System.nanoTime();
    }

    public static void end(int index) {
        if (!StreamingProbe.onStreamingOwner()) return;
        long elapsed = System.nanoTime() - started;
        calls[index]++;
        totals[index] += elapsed;
        maxima[index] = Math.max(maxima[index], elapsed);
        if (SITES.get(index).phase.equals("unload.adjacent")) ChunkLifecycleComparison.sample(false, elapsed);
    }

    @FunctionalInterface
    interface Samples {
        void accept(String phase, long calls, long total, long max);
    }

    static void drain(Samples samples) {
        for (int index = 0; index < SITES.size(); index++) {
            samples.accept("chunk.lifecycle." + SITES.get(index).phase, calls[index], totals[index], maxima[index]);
            calls[index] = totals[index] = maxima[index] = 0;
        }
    }
}
