package cloud.apocalipse.pz.agent.features.networkdiagnostics;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class NetworkDiagnosticsModule implements AgentModule {
    private static final String SERVER = "zombie.network.GameServer";
    private static final String PLAYER = "zombie.network.fields.character.ConnectionPlayer";
    private static final String PACKET = "zombie.network.packets.INetworkPacket";
    private static final String RECEIVE = "mainLoopDealWithNetData";
    private static final String PARSE = "parse";
    private static final String WARN = "logInconsistentPacket";
    private static final String READER = "Lzombie/core/network/ByteBufferReader;";
    private static final String CONNECTION = "Lzombie/network/IConnection;";
    private static final String UDP_CONNECTION = "Lzombie/core/raknet/UdpConnection;";
    private static final String PACKET_TYPE = "zombie/network/PacketTypes$PacketType";
    private static final String DEBUG_TYPE = "zombie/debug/DebugType";
    private static final String LOG_ARGUMENTS = "Ljava/lang/String;[Ljava/lang/Object;";
    private static final String HOOKS = Type.getInternalName(NetworkDiagnostics.class);

    @Override public String name() { return "network-diagnostics"; }

    @Override public String disabledReason() {
        return AgentConfiguration.booleanProperty(AgentConfiguration.NETWORK_DIAGNOSTICS_PROPERTY)
            ? null : "Disabled by -D" + AgentConfiguration.NETWORK_DIAGNOSTICS_PROPERTY;
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = NetworkDiagnostics.class.getName();
        return List.of(
            new HookCheck("receive-context", SERVER, RECEIVE, helper, "receive", false),
            new HookCheck("invalid-player-slot", PLAYER, PARSE, helper, "invalidSlot", false),
            new HookCheck("inconsistent-packet", PACKET, WARN, helper, "inconsistentPacket", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder
            .type(named(SERVER)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                    .method(named(RECEIVE).and(hasDescriptor("(Lzombie/network/ZomboidNetData;)V")),
                        (owner, method, visitor, context, pool, writer, reader) -> receiveVisitor(visitor))))
            .type(named(PLAYER)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(new AsmVisitorWrapper.ForDeclaredMethods().writerFlags(ClassWriter.COMPUTE_FRAMES)
                    .method(named(PARSE).and(hasDescriptor("(" + READER + CONNECTION + ")V")),
                        (owner, method, visitor, context, pool, writer, reader) -> invalidSlotVisitor(visitor))))
            .type(named(PACKET)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(InconsistentPacketAdvice.class)
                    .on(named(WARN).and(hasDescriptor("(" + CONNECTION + "L" + PACKET_TYPE + ";)V")))));
    }

    private static MethodVisitor receiveVisitor(MethodVisitor visitor) {
        return new MethodVisitor(ASM9, visitor) {
            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                  boolean isInterface) {
                if (opcode == INVOKEVIRTUAL && owner.equals(PACKET_TYPE) && name.equals("onServerPacket")
                        && descriptor.equals("(" + READER + UDP_CONNECTION + ")V")) {
                    super.visitMethodInsn(INVOKESTATIC, HOOKS, "receive",
                        "(L" + PACKET_TYPE + ";" + READER + UDP_CONNECTION + ")V", false);
                } else {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
            }
        };
    }

    private static MethodVisitor invalidSlotVisitor(MethodVisitor visitor) {
        return new MethodVisitor(ASM9, visitor) {
            @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                  boolean isInterface) {
                if (opcode == INVOKEVIRTUAL && owner.equals(DEBUG_TYPE) && name.equals("error")
                        && descriptor.equals("(" + LOG_ARGUMENTS + ")V")) {
                    Label vanillaLog = new Label();
                    Label afterLog = new Label();
                    // Keep the original receiver and arguments for the non-server or failure path.
                    super.visitInsn(DUP2);
                    super.visitVarInsn(ALOAD, 2);
                    super.visitMethodInsn(INVOKESTATIC, HOOKS, "invalidSlot",
                        "(" + LOG_ARGUMENTS + CONNECTION + ")Z", false);
                    super.visitJumpInsn(IFEQ, vanillaLog);
                    super.visitInsn(POP2);
                    super.visitInsn(POP);
                    super.visitJumpInsn(GOTO, afterLog);
                    super.visitLabel(vanillaLog);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitLabel(afterLog);
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        };
    }

    public static final class InconsistentPacketAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = RuntimeException.class)
        public static boolean enter(@Advice.This Object packet, @Advice.Argument(0) Object connection,
                                    @Advice.Argument(1) Object type) {
            return NetworkDiagnostics.inconsistentPacket(packet, connection, type);
        }
    }
}
