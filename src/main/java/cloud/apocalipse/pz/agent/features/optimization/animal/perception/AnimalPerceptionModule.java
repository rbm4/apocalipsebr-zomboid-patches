package cloud.apocalipse.pz.agent.features.optimization.animal.perception;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Keeps the original animal LOS loop and reaction logic on the scheduler owner. */
public final class AnimalPerceptionModule extends OptimizationModule {
    private static final String ANIMAL = "zombie.characters.animals.IsoAnimal";
    private static final String BEHAVIOR = "zombie.characters.animals.behavior.BaseAnimalBehavior";
    private static final String CELL = "zombie.iso.IsoCell";
    private static final String MOVING = "zombie.iso.IsoMovingObject";
    private static final String SCHEDULER = "zombie.MovingObjectUpdateScheduler";
    private static final String ANIMAL_TYPE = "Lzombie/characters/animals/IsoAnimal;";
    private static final String SPOTTED = "(Lzombie/iso/IsoMovingObject;ZF)V";

    public AnimalPerceptionModule() {
        super("animal-perception", AgentConfiguration.ANIMAL_PERCEPTION_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = ServerAnimalPerception.class.getName();
        return List.of(
            new HookCheck("scheduler-enter", SCHEDULER, "update", helper, "beginFrame", false),
            new HookCheck("scheduler-exit", SCHEDULER, "update", helper, "endFrame", false),
            new HookCheck("periodic-los", ANIMAL, "updateInternal", helper, "updateLos", false),
            new HookCheck("candidate-query", ANIMAL, "updateLOS", helper, "candidates", false),
            new HookCheck("candidate-reaction", ANIMAL, "updateLOS", helper, "spotted", false),
            new HookCheck("reaction-bookkeeping", BEHAVIOR, "spotted", helper, "alreadyPrepared", false),
            new HookCheck("object-membership", CELL, "<init>", helper, "track", false),
            new HookCheck("zombie-move-x", MOVING, "setX", helper, "moved", false),
            new HookCheck("zombie-move-y", MOVING, "setY", helper, "moved", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(SCHEDULER)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(SchedulerFrame.class).on(named("update").and(hasDescriptor("()V")))))
            .type(named(ANIMAL)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(OptimizationVisitor.method("updateInternal", "()V", UpdateCall::new))
                .visit(OptimizationVisitor.method("updateLOS", "()V", LosCalls::new).writerFlags(ClassWriter.COMPUTE_MAXS)))
            .type(named(BEHAVIOR)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(OptimizationVisitor.method("spotted", SPOTTED, Reaction::new).writerFlags(ClassWriter.COMPUTE_FRAMES)))
            .type(named(CELL)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(new AsmVisitorWrapper.ForDeclaredMethods().constructor(hasDescriptor("(II)V"),
                    (target, method, downstream, context, pool, writer, reader) -> new CellObjects(downstream))))
            .type(named(MOVING)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(MoveX.class).on(named("setX").and(hasDescriptor("(F)F"))))
                .visit(Advice.to(MoveY.class).on(named("setY").and(hasDescriptor("(F)F")))));
    }

    public static class SchedulerFrame {
        @Advice.OnMethodEnter
        public static ServerAnimalPerception.Frame enter(@Advice.FieldValue("frameCounter") long tick) {
            return ServerAnimalPerception.beginFrame(tick);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter ServerAnimalPerception.Frame frame) {
            ServerAnimalPerception.endFrame(frame);
        }
    }

    public static class MoveX {
        @Advice.OnMethodEnter
        public static float enter(@Advice.FieldValue("x") float before) { return before; }

        @Advice.OnMethodExit
        public static void exit(@Advice.This Object object, @Advice.Enter float before,
                                @Advice.Argument(0) float after) {
            ServerAnimalPerception.moved(object, before, after);
        }
    }

    public static class MoveY {
        @Advice.OnMethodEnter
        public static float enter(@Advice.FieldValue("y") float before) { return before; }

        @Advice.OnMethodExit
        public static void exit(@Advice.This Object object, @Advice.Enter float before,
                                @Advice.Argument(0) float after) {
            ServerAnimalPerception.moved(object, before, after);
        }
    }

    private static final class UpdateCall extends MethodVisitor {
        private int matches;

        UpdateCall(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKEVIRTUAL && owner.equals(ANIMAL.replace('.', '/'))
                    && name.equals("updateLOS") && descriptor.equals("()V")) {
                matches++;
                OptimizationVisitor.callStatic(mv, ServerAnimalPerception.class, "updateLos", "(" + ANIMAL_TYPE + ")V");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("animal periodic LOS", matches, 1);
            super.visitEnd();
        }
    }

    private static final class LosCalls extends MethodVisitor {
        private int iteratorMatches;
        private int reactionMatches;

        LosCalls(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKEINTERFACE && owner.equals("java/util/Set")
                    && name.equals("iterator") && descriptor.equals("()Ljava/util/Iterator;")) {
                iteratorMatches++;
                mv.visitVarInsn(ALOAD, 0);
                OptimizationVisitor.callStatic(mv, ServerAnimalPerception.class, "candidates",
                    "(Ljava/util/Set;" + ANIMAL_TYPE + ")Ljava/util/Iterator;");
                return;
            }
            if (opcode == INVOKEVIRTUAL && owner.equals(BEHAVIOR.replace('.', '/'))
                    && name.equals("spotted") && descriptor.equals(SPOTTED)) {
                reactionMatches++;
                mv.visitVarInsn(ALOAD, 0);
                OptimizationVisitor.callStatic(mv, ServerAnimalPerception.class, "spotted",
                    "(Lzombie/characters/animals/behavior/BaseAnimalBehavior;Lzombie/iso/IsoMovingObject;ZF" + ANIMAL_TYPE + ")V");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("animal LOS iterator", iteratorMatches, 1);
            OptimizationVisitor.requireMatches("animal LOS reactions", reactionMatches, 2);
            super.visitEnd();
        }
    }

    private static final class Reaction extends MethodVisitor {
        private final Label prepared = new Label();
        private int matches;

        Reaction(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override public void visitCode() {
            super.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            OptimizationVisitor.callStatic(mv, ServerAnimalPerception.class, "alreadyPrepared",
                "(Lzombie/characters/animals/behavior/BaseAnimalBehavior;)Z");
            mv.visitJumpInsn(IFNE, prepared);
        }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == GETSTATIC && owner.equals("zombie/network/GameClient")
                    && name.equals("client") && descriptor.equals("Z") && matches++ == 0) {
                mv.visitLabel(prepared);
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("animal reaction client guards", matches, 2);
            super.visitEnd();
        }
    }

    private static final class CellObjects extends MethodVisitor {
        private int matches;

        CellObjects(MethodVisitor downstream) { super(ASM9, downstream); }

        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == PUTFIELD && owner.equals("zombie/iso/IsoCell")
                    && name.equals("objectList") && descriptor.equals("Ljava/util/Set;")) {
                matches++;
                OptimizationVisitor.callStatic(mv, ServerAnimalPerception.class, "track",
                    "(Ljava/util/Set;)Ljava/util/Set;");
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("cell moving-object set", matches, 1);
            super.visitEnd();
        }
    }
}
