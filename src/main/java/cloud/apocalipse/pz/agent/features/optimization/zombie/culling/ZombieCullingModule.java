package cloud.apocalipse.pz.agent.features.optimization.zombie.culling;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class ZombieCullingModule extends OptimizationModule {
    private static final String TARGET = "zombie.popman.ZombieCountOptimiser";
    private static final String METHOD = "prepareZombiesForDeletion";

    public ZombieCullingModule() {
        super("zombie-culling", AgentConfiguration.ZOMBIE_CULLING_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("quota", TARGET, METHOD, ZombieCullingModule.class.getName(),
            "recordQuotaExit", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method(METHOD, "()V", QuotaVisitor::new)
                .writerFlags(ClassWriter.COMPUTE_FRAMES).readerFlags(ClassReader.SKIP_FRAMES)));
    }

    public static void recordQuotaExit(boolean fulfilled) {
        OptimizationTelemetry.count(fulfilled
            ? OptimizationTelemetry.Counter.CULLING_FULFILLED_QUOTA
            : OptimizationTelemetry.Counter.CULLING_ZERO_QUOTA);
    }

    private static final class QuotaVisitor extends MethodVisitor {
        // Slots in 42.22.2 prepareZombiesForDeletion()V.
        private static final int QUOTA = 4;
        private Label nextConnection;
        private boolean pendingQuotaStore;
        private int initialGuards;
        private int fulfilledGuards;

        QuotaVisitor(MethodVisitor visitor) {
            super(ASM9, visitor);
        }

        @Override
        public void visitJumpInsn(int opcode, Label target) {
            if (opcode == IFNULL && nextConnection == null) {
                // The null recipient-list branch already jumps to the outer-loop increment.
                nextConnection = target;
            }
            super.visitJumpInsn(opcode, target);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            pendingQuotaStore = opcode == INVOKESTATIC && owner.equals("java/lang/Math")
                && name.equals("max") && descriptor.equals("(II)I");
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            super.visitVarInsn(opcode, slot);
            if (pendingQuotaStore && opcode == ISTORE && slot == QUOTA) {
                guard(false);
                initialGuards++;
            }
            pendingQuotaStore = false;
        }

        @Override
        public void visitIincInsn(int slot, int increment) {
            super.visitIincInsn(slot, increment);
            if (slot == QUOTA && increment == -1) {
                guard(true);
                fulfilledGuards++;
            }
        }

        private void guard(boolean fulfilled) {
            if (nextConnection == null) {
                throw new IllegalStateException("Culling connection-loop exit was not found");
            }
            Label positiveQuota = new Label();
            mv.visitVarInsn(ILOAD, QUOTA);
            mv.visitJumpInsn(IFGT, positiveQuota);
            mv.visitInsn(fulfilled ? ICONST_1 : ICONST_0);
            OptimizationVisitor.callStatic(mv, ZombieCullingModule.class, "recordQuotaExit", "(Z)V");
            mv.visitJumpInsn(GOTO, nextConnection);
            mv.visitLabel(positiveQuota);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("culling initial quota", initialGuards, 1);
            OptimizationVisitor.requireMatches("culling fulfilled quota", fulfilledGuards, 1);
            super.visitEnd();
        }
    }
}
