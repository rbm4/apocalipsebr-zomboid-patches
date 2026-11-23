package cloud.apocalipse.pz.agent.features.optimization.streaming.blending;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import zombie.iso.worldgen.blending.BlendDirection;

import static net.bytebuddy.matcher.ElementMatchers.named;

/** Shares the enum order at the two internal read-only array consumers. */
public final class BlendDirectionsModule extends OptimizationModule {
    private static final String BLENDING = "zombie.iso.worldgen.blending.Blending";
    private static final String DIRECTION = "zombie.iso.worldgen.blending.BlendDirection";
    private static final String OWNER = DIRECTION.replace('.', '/');
    private static final String ARRAY = "()[L" + OWNER + ";";

    public BlendDirectionsModule() {
        super("chunk-blend-directions", AgentConfiguration.BLEND_DIRECTIONS_OPTIMIZATION);
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = BlendDirectionsModule.class.getName();
        return List.of(new HookCheck("blending-loop", BLENDING, "applyBlending", helper, "directions", false),
            new HookCheck("opposite-direction", DIRECTION, "opposite", helper, "directions", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(BLENDING)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("applyBlending", "(Lzombie/iso/IsoChunk;)V", ValuesVisitor::new)))
            .type(named(DIRECTION)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(OptimizationVisitor.method("opposite", "()L" + OWNER + ";", ValuesVisitor::new)));
    }

    // Called only from the two injected read-only sites. The game still exposes its cloning values() method.
    public static BlendDirection[] directions() { return DirectionOrder.VALUES; }

    private static final class DirectionOrder {
        private static final BlendDirection[] VALUES = BlendDirection.values();
    }

    private static final class ValuesVisitor extends MethodVisitor {
        private int matches;

        ValuesVisitor(MethodVisitor downstream) { super(Opcodes.ASM9, downstream); }

        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC && OWNER.equals(owner) && "values".equals(name) && ARRAY.equals(descriptor)) {
                OptimizationVisitor.callStatic(mv, BlendDirectionsModule.class, "directions", ARRAY);
                matches++;
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            }
        }

        @Override public void visitEnd() {
            OptimizationVisitor.requireMatches("blend direction values", matches, 1);
            super.visitEnd();
        }
    }
}

