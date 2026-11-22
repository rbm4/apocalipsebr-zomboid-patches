package cloud.apocalipse.pz.agent.features.optimization.player.thermal;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.pool.TypePool;
import zombie.characterTextures.BloodBodyPartType;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.network.GameServer;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class ThermalClothingModule extends OptimizationModule {
    private static final String TARGET = "zombie.characters.BodyDamage.Thermoregulator";
    private static final String OWNER = TARGET.replace('.', '/');
    private static final String STATE = ClothingState.class.getName().replace('.', '/');
    private static final String STATE_TYPE = "L" + STATE + ";";
    private static final String STATE_FIELD = "apocbrClothingState";

    public ThermalClothingModule() {
        super("thermal-clothing-cache", AgentConfiguration.THERMAL_CLOTHING_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("clothing-rebuild", TARGET, "updateClothing", ThermalClothingModule.class.getName(),
            "recordRebuild", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        // The agent installs at premain. This transformer changes the shape on initial definition only.
        return builder.type(named(TARGET)).and(notPreviouslyLoaded()).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(new ClothingVisitor())
                .visit(Advice.to(ClothingTiming.class).on(named("updateClothing").and(takesArguments(0)))));
    }

    private static AgentBuilder.RawMatcher notPreviouslyLoaded() {
        return (type, loader, module, classBeingRedefined, domain) -> classBeingRedefined == null;
    }

    public static void recordRebuild() {
        if (GameServer.server) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.CLOTHING_REBUILT);
        }
    }

    public static final class ClothingState {
        public final ItemVisuals itemVisuals = new ItemVisuals();
        public final ItemVisuals itemVisualsCache = new ItemVisuals();
        public final ArrayList<BloodBodyPartType> coveredParts = new ArrayList<>();
    }

    public static final class ClothingTiming {
        @Advice.OnMethodEnter
        public static long enter() {
            return GameServer.server ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) {
            if (started != 0L) {
                OptimizationTelemetry.Phase.THERMAL_CLOTHING.recordSince(started);
            }
        }
    }

    private static final class ClothingVisitor extends AsmVisitorWrapper.AbstractBase {
        @Override
        public int mergeWriter(int flags) {
            return flags | ClassWriter.COMPUTE_MAXS;
        }

        @Override
        public ClassVisitor wrap(TypeDescription type, ClassVisitor downstream, Implementation.Context context,
                TypePool pool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods,
                int writerFlags, int readerFlags) {
            return new ClassVisitor(ASM9, downstream) {
                private int constructors;
                private int updates;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (name.equals("<init>") && descriptor.equals("(Lzombie/characters/BodyDamage/BodyDamage;)V")) {
                        constructors++;
                        return new StateInitializer(method);
                    }
                    if (name.equals("updateClothing") && descriptor.equals("()V")) {
                        updates++;
                        return new ClothingReads(method);
                    }
                    return method;
                }

                @Override
                public void visitEnd() {
                    OptimizationVisitor.requireMatches("thermal constructor", constructors, 1);
                    OptimizationVisitor.requireMatches("thermal clothing method", updates, 1);
                    super.visitField(ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC, STATE_FIELD, STATE_TYPE, null, null).visitEnd();
                    super.visitEnd();
                }
            };
        }
    }

    private static final class StateInitializer extends MethodVisitor {
        StateInitializer(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == RETURN) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitTypeInsn(NEW, STATE);
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, STATE, "<init>", "()V", false);
                mv.visitFieldInsn(PUTFIELD, OWNER, STATE_FIELD, STATE_TYPE);
            }
            super.visitInsn(opcode);
        }
    }

    private static final class ClothingReads extends MethodVisitor {
        private int currentReads;
        private int cachedReads;
        private int coveredReads;
        private boolean cacheOnStack;
        private int rebuilds;

        ClothingReads(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            cacheOnStack = false;
            if (opcode == GETSTATIC && owner.equals(OWNER)) {
                switch (name) {
                    case "itemVisuals" -> currentReads++;
                    case "itemVisualsCache" -> {
                        cachedReads++;
                        cacheOnStack = true;
                    }
                    case "coveredParts" -> coveredReads++;
                    default -> {
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                        return;
                    }
                }
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, OWNER, STATE_FIELD, STATE_TYPE);
                mv.visitFieldInsn(GETFIELD, STATE, name, descriptor);
                return;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (cacheOnStack && name.equals("clear") && descriptor.equals("()V")
                    && owner.equals("zombie/core/skinnedmodel/visual/ItemVisuals")) {
                rebuilds++;
                OptimizationVisitor.callStatic(mv, ThermalClothingModule.class, "recordRebuild", "()V");
            }
            cacheOnStack = false;
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("thermal current visuals", currentReads, 6);
            OptimizationVisitor.requireMatches("thermal cached visuals", cachedReads, 5);
            OptimizationVisitor.requireMatches("thermal covered parts", coveredReads, 4);
            OptimizationVisitor.requireMatches("thermal rebuild", rebuilds, 1);
            super.visitEnd();
        }
    }
}
