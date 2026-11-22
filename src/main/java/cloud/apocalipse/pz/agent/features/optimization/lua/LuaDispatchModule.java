package cloud.apocalipse.pz.agent.features.optimization.lua;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class LuaDispatchModule extends OptimizationModule {
    private static final String TARGET = "zombie.Lua.Event";

    public LuaDispatchModule() {
        super("lua-stable-dispatch", AgentConfiguration.LUA_DISPATCH_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("membership", TARGET, "trigger", LuaDispatchModule.class.getName(),
            "stillRegistered", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(OptimizationVisitor.method("trigger",
                "(Lse/krka/kahlua/vm/KahluaTable;Lse/krka/kahlua/integration/LuaCaller;[Ljava/lang/Object;)Z",
                MembershipVisitor::new).writerFlags(ClassWriter.COMPUTE_MAXS)));
    }

    public static boolean stillRegistered(ArrayList<?> callbacks, Object closure, int index) {
        if (index < callbacks.size() && callbacks.get(index) == closure) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.LUA_MEMBERSHIP_SCAN_SKIPPED);
            return true;
        }
        return callbacks.contains(closure);
    }

    private static final class MembershipVisitor extends MethodVisitor {
        private int matches;

        MembershipVisitor(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKEVIRTUAL && owner.equals("java/util/ArrayList") && name.equals("contains")
                    && descriptor.equals("(Ljava/lang/Object;)Z")) {
                matches++;
                // Vanilla local 5 is the callback index in both profiling branches.
                mv.visitVarInsn(ILOAD, 5);
                OptimizationVisitor.callStatic(mv, LuaDispatchModule.class, "stillRegistered",
                    "(Ljava/util/ArrayList;Ljava/lang/Object;I)Z");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("Lua callback membership", matches, 2);
            super.visitEnd();
        }
    }
}
