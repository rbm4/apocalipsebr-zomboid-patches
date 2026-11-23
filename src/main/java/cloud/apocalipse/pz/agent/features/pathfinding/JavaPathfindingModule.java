package cloud.apocalipse.pz.agent.features.pathfinding;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.HookPreflight;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

/** Locks the vanilla backend before world startup and throughout simulation. */
public final class JavaPathfindingModule implements AgentModule {
    private static final String NATIVE = "zombie.pathfind.nativeCode.PathfindNative";
    private static final String NATIVE_INTERNAL = NATIVE.replace('.', '/');
    private static final String WORLD = "zombie.iso.IsoWorld";
    private static final String SERVER_MAP = "zombie.network.ServerMap";
    private static final String HOOKS = JavaPathfindingHooks.class.getName();
    private static final String HOOKS_INTERNAL = HOOKS.replace('.', '/');
    private boolean enabled;

    @Override public String name() { return "java-pathfinding"; }

    @Override public void initialize() {
        enabled = AgentConfiguration.forceJavaPathfinding();
        if (enabled) {
            HookPreflight.validate(List.of(NATIVE, WORLD, SERVER_MAP), hookChecks(), JavaPathfindingModule::transform);
        }
    }

    @Override public String disabledReason() {
        return enabled ? null : "Disabled by -D" + AgentConfiguration.FORCE_JAVA_PATHFINDING_PROPERTY + "=false";
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(
            new HookCheck("initial-backend", NATIVE, "<clinit>", HOOKS, "useNativeCode", false),
            new HookCheck("world-backend", WORLD, "init", HOOKS, "useNativeCode", false),
            new HookCheck("lock-backend", NATIVE, "checkUseNativeCode", HOOKS, "useNativeCode", false),
            new HookCheck("server-quit", SERVER_MAP, "QueuedQuit", HOOKS, "stopJavaWorker", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder.type(namedOneOf(NATIVE, WORLD, SERVER_MAP))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic));
    }

    private static DynamicType.Builder<?> transform(String target, DynamicType.Builder<?> builder) {
        return switch (target) {
            case NATIVE -> builder.visit(backendWrite(isTypeInitializer()))
                .visit(Advice.to(LockBackend.class).on(named("checkUseNativeCode").and(hasDescriptor("()V"))));
            case WORLD -> builder.visit(backendWrite(named("init").and(hasDescriptor("()V"))));
            case SERVER_MAP -> builder.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                .method(named("QueuedQuit").and(hasDescriptor("()V")),
                    (type, method, downstream, context, pool, writer, reader) -> new MethodVisitor(Opcodes.ASM9, downstream) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(NATIVE_INTERNAL)
                                    && name.equals("stop") && descriptor.equals("()V")) {
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL, "stopJavaWorker", "()V", false);
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }));
            default -> throw new IllegalArgumentException("Unexpected pathfinding target: " + target);
        };
    }

    private static AsmVisitorWrapper backendWrite(ElementMatcher<? super MethodDescription> matcher) {
        return new AsmVisitorWrapper.ForDeclaredMethods()
            .invokable(matcher,
                (type, method, downstream, context, pool, writer, reader) -> new MethodVisitor(Opcodes.ASM9, downstream) {
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.PUTSTATIC && owner.equals(NATIVE_INTERNAL)
                                && name.equals("useNativeCode") && descriptor.equals("Z")) {
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS_INTERNAL, "useNativeCode", "()Z", false);
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                });
    }

    public static final class LockBackend {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean skipSwitch() {
            return !JavaPathfindingHooks.useNativeCode();
        }
    }
}
