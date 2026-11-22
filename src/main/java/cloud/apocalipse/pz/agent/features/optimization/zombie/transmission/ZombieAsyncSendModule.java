package cloud.apocalipse.pz.agent.features.optimization.zombie.transmission;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import java.util.List;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.characters.IsoZombie;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.core.utils.UpdateLimit;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.popman.NetworkZombiePacker;
import zombie.popman.NetworkZombiePacker.DeletedZombie;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class ZombieAsyncSendModule implements AgentModule {
    private static final String PACKER = "zombie.popman.NetworkZombiePacker";
    private static final String ENGINE = "zombie.core.raknet.UdpEngine";
    private static volatile ZombieSendRuntime runtime;

    @Override
    public String name() {
        return "zombie-async-transmission";
    }

    @Override
    public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(AgentConfiguration.ZOMBIE_ASYNC_SEND_OPTIMIZATION)
            ? null : "Disabled by -D" + AgentConfiguration.ZOMBIE_ASYNC_SEND_OPTIMIZATION;
    }

    @Override
    public List<HookCheck> hookChecks() {
        String helper = getClass().getName();
        return List.of(new HookCheck("dispatch", PACKER, "postupdate", helper, "dispatch", false),
            new HookCheck("connection-open", ENGINE, "addConnection", helper, "connect", false),
            new HookCheck("connection-close", ENGINE, "removeConnection", helper, "disconnect", false),
            new HookCheck("connection-force-close", ENGINE, "forceDisconnect", helper, "disconnect", false),
            new HookCheck("peer-stop", ENGINE, "Shutdown", helper, "shutdown", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(PACKER)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(OptimizationVisitor.method("postupdate", "()V", DispatchVisitor::new)
                .writerFlags(ClassWriter.COMPUTE_FRAMES).readerFlags(ClassReader.SKIP_FRAMES)))
            .type(named(ENGINE)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Connect.class).on(named("addConnection").and(hasDescriptor("(IJ)Lzombie/core/raknet/UdpConnection;"))))
                .visit(Advice.to(Disconnect.class).on(named("removeConnection").and(hasDescriptor("(J)V"))))
                .visit(Advice.to(Disconnect.class).on(named("forceDisconnect").and(hasDescriptor("(JLjava/lang/String;)V"))))
                .visit(Advice.to(Shutdown.class).on(named("Shutdown").and(hasDescriptor("()V")))));
    }

    private static synchronized ZombieSendRuntime runtimeFor(UdpEngine engine) {
        if (runtime == null || runtime.engine() != engine) {
            if (runtime != null) {
                runtime.shutdown();
            }
            runtime = new ZombieSendRuntime(engine);
        }
        return runtime;
    }

    public static boolean dispatch(NetworkZombiePacker packer, List<IsoZombie> processing, List<DeletedZombie> deleted,
            Set<IConnection> extra, UpdateLimit limiter) {
        if (!GameServer.server || GameServer.udpEngine == null) {
            return false;
        }
        runtimeFor(GameServer.udpEngine).dispatch(packer, processing, deleted, extra, limiter);
        return true;
    }

    public static void connect(Object engine, Object connection, boolean server) {
        if (server && connection != null) {
            runtimeFor((UdpEngine) engine).connect((UdpConnection) connection);
        }
    }

    public static void disconnect(Object engine, long guid) {
        ZombieSendRuntime current = runtime;
        if (current != null && current.engine() == engine) {
            current.disconnect(guid);
        }
    }

    public static void shutdown(Object engine) {
        ZombieSendRuntime current = runtime;
        if (current != null && current.engine() == engine) {
            current.shutdown();
        }
    }

    public static class Connect {
        @Advice.OnMethodExit
        public static void exit(@Advice.This Object engine, @Advice.Return Object connection,
                @Advice.FieldValue("server") boolean server) { connect(engine, connection, server); }
    }

    public static class Disconnect {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object engine, @Advice.Argument(0) long guid) { disconnect(engine, guid); }
    }

    public static class Shutdown {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object engine) { shutdown(engine); }
    }

    private static final class DispatchVisitor extends MethodVisitor {
        private static final String OWNER = PACKER.replace('.', '/');
        private int matches;

        DispatchVisitor(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == GETSTATIC && owner.equals("zombie/network/GameServer") && name.equals("udpEngine")
                    && descriptor.equals("Lzombie/core/raknet/UdpEngine;")) {
                matches++;
                mv.visitVarInsn(ALOAD, 0);
                readField("zombiesProcessing", "Ljava/util/ArrayList;");
                readField("zombiesDeletedForSending", "Ljava/util/ArrayList;");
                readField("extraUpdate", "Ljava/util/HashSet;");
                readField("zombieSynchronizationReliableLimit", "Lzombie/core/utils/UpdateLimit;");
                OptimizationVisitor.callStatic(mv, ZombieAsyncSendModule.class, "dispatch",
                    "(L" + OWNER + ";Ljava/util/List;Ljava/util/List;Ljava/util/Set;Lzombie/core/utils/UpdateLimit;)Z");
                Label vanilla = new Label();
                mv.visitJumpInsn(IFEQ, vanilla);
                mv.visitInsn(RETURN);
                mv.visitLabel(vanilla);
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        private void readField(String field, String descriptor) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, OWNER, field, descriptor);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("zombie postupdate dispatch boundary", matches, 1);
            super.visitEnd();
        }
    }
}
