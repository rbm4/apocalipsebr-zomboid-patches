package cloud.apocalipse.pz.agent.integration.probe;

import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.optimization.streaming.ChunkAdjacencyModule;
import cloud.apocalipse.pz.agent.features.optimization.streaming.ChunkContainerModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.core.properties.IsoPropertyType;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Runs interleaved vanilla and optimized chunk cohorts on one server. */
public final class ChunkLifecycleComparison {
    private static final String CHUNK = "zombie.iso.IsoChunk";
    private static final String OBJECT = "zombie.iso.IsoObject";
    private static final String SQUARE_ARG = "(Lzombie/iso/IsoGridSquare;)V";
    private static final String CONTAINER = "createContainersFromSpriteProperties";
    private static final Sample[][][] samples = new Sample[5][2][2];
    private static final Object OUTSIDE = new Object();
    private static Sample current;
    private static int checkedInteriors, checkedEdges, checkedContainers;
    private static boolean checkedContainerFixture;

    private ChunkLifecycleComparison() {}

    static AgentBuilder configure(AgentBuilder builder) {
        return builder.type(named(CHUNK)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(Scope.class).on(namedOneOf("doLoadGridsquare", "removeFromWorld").and(hasDescriptor("()V"))))
                .visit(OptimizationVisitor.method("disconnectFromAdjacentChunks", SQUARE_ARG,
                    visitor -> new HookGate(visitor, ChunkAdjacencyModule.class, "unlink"))))
            .type(named(OBJECT)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method(CONTAINER, "()V",
                    visitor -> new HookGate(visitor, ChunkContainerModule.class, "skipContainerCheck")))
                    .visit(OptimizationVisitor.method("addToWorld", "()V", ContainerTimer::new)));
    }

    public static Object enter(Object receiver, String name) {
        Sample previous = current;
        int cycle = StreamingProbe.activeCycle();
        if (cycle < 0) return OUTSIDE;
        IsoChunk chunk = (IsoChunk) receiver;
        int arm = cycle == 0 ? 1 : (chunk.wx + chunk.wy + (cycle <= 2 ? cycle : cycle + 1)) & 1;
        int stage = name.equals("doLoadGridsquare") ? 0 : 1;
        Sample[] slot = samples[cycle][stage];
        if (slot[arm] == null) slot[arm] = new Sample(cycle, stage == 0, arm == 1);
        current = slot[arm];
        current.chunks++;
        return previous;
    }

    public static void leave(Object previous) { if (previous != OUTSIDE) current = (Sample) previous; }

    public static boolean skipContainerCheck(Object object, Object rawSprite, Object container) {
        Sample sample = current;
        if (sample == null || !StreamingProbe.onStreamingOwner()) return ChunkContainerModule.skipContainerCheck(object, rawSprite, container);
        if (!sample.enabled) return false;
        boolean skipped = ChunkContainerModule.skipContainerCheck(object, rawSprite, container);
        if (sample.cycle == 0 && skipped && !checkedContainerFixture) {
            checkedContainerFixture = true;
            checkContainers();
        }
        if (skipped) sample.saved++;
        return skipped;
    }

    private static void checkContainers() {
        var sprite = IsoSprite.CreateSprite(IsoSpriteManager.instance);
        var object = new IsoObject(null, null, sprite);
        object.createContainersFromSpriteProperties();
        if (object.getContainer() != null) throw new IllegalStateException("Plain object gained a container");
        checkedContainers++;

        sprite.getProperties().set(IsoFlagType.container);
        sprite.getProperties().set(IsoPropertyType.CONTAINER, "counter", false);
        object.createContainersFromSpriteProperties();
        var container = object.getContainer();
        if (container == null || !"counter".equals(container.getType())) {
            throw new IllegalStateException("Native sprite container was not created");
        }
        checkedContainers++;
        object.createContainersFromSpriteProperties();
        if (object.getContainer() != container) throw new IllegalStateException("Existing container was replaced");
        checkedContainers++;

        var freezerSprite = IsoSprite.CreateSprite(IsoSpriteManager.instance);
        freezerSprite.getProperties().set("Freezer", "", false);
        var freezer = new IsoObject(null, null, freezerSprite);
        freezer.createContainersFromSpriteProperties();
        if (freezer.getContainer() == null || !"freezer".equals(freezer.getContainer().getType())) {
            throw new IllegalStateException("Native freezer-only sprite container was not created");
        }
        checkedContainers++;
    }

    public static boolean unlink(Object receiver) {
        Sample sample = current;
        if (sample == null || !StreamingProbe.onStreamingOwner()) return ChunkAdjacencyModule.unlink(receiver);
        if (!sample.enabled) return false;
        if (sample.cycle == 0 && (checkedEdges < 128 || checkedInteriors < 128)) {
            IsoGridSquare square = (IsoGridSquare) receiver;
            return checkLinks(square, isBorder(square));
        }
        return ChunkAdjacencyModule.unlink(receiver);
    }

    public static void observeUnlink(Object receiver) {
        Sample sample = current;
        if (sample != null && sample.enabled && StreamingProbe.onStreamingOwner() && !isBorder((IsoGridSquare) receiver)) {
            sample.saved++;
        }
    }

    private static boolean isBorder(IsoGridSquare square) {
        int x = square.x & 7;
        int y = square.y & 7;
        return x == 0 || x == 7 || y == 0 || y == 7;
    }

    private static boolean checkLinks(IsoGridSquare square, boolean border) {
        var adjacent = square.getSurroundingSquares().clone();
        var reverse = new IsoGridSquare[8];
        var aliases = new IsoGridSquare[8];
        for (int direction = 0; direction < 8; direction++) {
            var neighbor = adjacent[direction];
            int opposite = (direction + 4) & 7;
            if (neighbor != null) {
                reverse[direction] = neighbor.getSurroundingSquares()[opposite];
                aliases[direction] = alias(neighbor, opposite);
            }
        }
        if (!ChunkAdjacencyModule.unlink(square)) throw new IllegalStateException("Expected owner-thread adjacency hook");
        for (int direction = 0; direction < 8; direction++) {
            var neighbor = adjacent[direction];
            int opposite = (direction + 4) & 7;
            if (neighbor == null) continue;
            boolean clear = border && neighbor.chunk != square.chunk;
            if (neighbor.getSurroundingSquares()[opposite] != (clear ? null : reverse[direction])
                    || alias(neighbor, opposite) != (clear ? null : aliases[direction])) {
                throw new IllegalStateException("Unexpected reverse link after chunk detachment");
            }
            if (clear) checkedEdges++;
            else if (!border) checkedInteriors++;
        }
        return true;
    }

    private static IsoGridSquare alias(IsoGridSquare square, int direction) {
        return switch (direction) {
            case 0 -> square.n;
            case 1 -> square.nw;
            case 2 -> square.w;
            case 3 -> square.sw;
            case 4 -> square.s;
            case 5 -> square.se;
            case 6 -> square.e;
            case 7 -> square.ne;
            default -> throw new IllegalArgumentException("Invalid direction");
        };
    }

    private static long containerStarted;

    public static void startContainer() {
        if (current != null && StreamingProbe.onStreamingOwner()) containerStarted = System.nanoTime();
    }
    public static void endContainer() {
        if (current != null && StreamingProbe.onStreamingOwner()) sample(true, System.nanoTime() - containerStarted);
    }

    static void sample(boolean load, long elapsed) {
        if (current == null || current.load != load) return;
        current.calls++;
        current.nanos += elapsed;
    }

    static void write(Path directory) throws IOException {
        var csv = new StringBuilder("cycle,load,enabled,chunks,calls,saved,nanos\n");
        for (var cycle : samples) {
            for (var stage : cycle) {
                for (var sample : stage) {
                    if (sample != null) {
                        csv.append(sample.cycle).append(',').append(sample.load).append(',').append(sample.enabled)
                            .append(',').append(sample.chunks).append(',').append(sample.calls).append(',')
                            .append(sample.saved).append(',').append(sample.nanos).append('\n');
                    }
                }
            }
        }
        Files.writeString(directory.resolve("chunk-lifecycle-comparison.csv"), csv);
        Files.writeString(directory.resolve("chunk-lifecycle-oracles.txt"),
            checkedInteriors + "," + checkedEdges + "," + checkedContainers);
    }

    public static class Scope {
        @Advice.OnMethodEnter public static Object enterScope(@Advice.This Object chunk, @Advice.Origin("#m") String name) {
            return enter(chunk, name);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exitScope(@Advice.Enter Object previous) { leave(previous); }
    }

    private static final class HookGate extends MethodVisitor {
        private final String owner;
        private final String helper;
        private int count;

        HookGate(MethodVisitor next, Class<?> module, String helper) {
            super(ASM9, next);
            this.owner = module.getName().replace('.', '/');
            this.helper = helper;
        }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && owner.equals(this.owner) && name.equals(helper)) {
                OptimizationVisitor.callStatic(mv, ChunkLifecycleComparison.class, name, descriptor);
                count++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches(helper, count, 1);
            super.visitEnd();
        }
    }

    private static final class ContainerTimer extends MethodVisitor {
        private int count;
        ContainerTimer(MethodVisitor next) { super(ASM9, next); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            boolean matched = owner.equals(OBJECT.replace('.', '/')) && name.equals(CONTAINER) && descriptor.equals("()V");
            if (matched) OptimizationVisitor.callStatic(mv, ChunkLifecycleComparison.class, "startContainer", "()V");
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            if (matched) {
                OptimizationVisitor.callStatic(mv, ChunkLifecycleComparison.class, "endContainer", "()V");
                count++;
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("IsoObject.addToWorld container call", count, 1);
            super.visitEnd();
        }
    }

    private static final class Sample {
        final int cycle;
        final boolean load, enabled;
        int chunks;
        long calls, saved, nanos;

        Sample(int cycle, boolean load, boolean enabled) {
            this.cycle = cycle;
            this.load = load;
            this.enabled = enabled;
        }
    }
}
