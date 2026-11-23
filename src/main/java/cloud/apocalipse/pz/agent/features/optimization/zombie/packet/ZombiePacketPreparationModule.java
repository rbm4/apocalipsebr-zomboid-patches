package cloud.apocalipse.pz.agent.features.optimization.zombie.packet;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationVisitor;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import zombie.network.GameServer;

import static net.bytebuddy.jar.asm.Opcodes.*;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class ZombiePacketPreparationModule extends OptimizationModule {
    private static final String AI = "zombie.characters.NetworkZombieAI";
    private static final String PACKET = "zombie.network.packets.character.ZombiePacket";
    private static final String PACKET_ARGUMENT = "(Lzombie/network/packets/character/ZombiePacket;";
    private static final String ZOMBIE_ARGUMENT = "(Lzombie/characters/IsoZombie;";
    private static final String VECTOR = "zombie/iso/Vector2";
    private static final String WALK_TYPE = "zombie/network/NetworkVariables$WalkType";
    private static final String STATE = "zombie/network/NetworkVariables$ZombieState";

    public ZombiePacketPreparationModule() {
        super("zombie-packet-local-preparation", AgentConfiguration.ZOMBIE_PACKET_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        String work = ZombiePacketWork.class.getName();
        return List.of(
            new HookCheck("ai-flags", AI, "set", work, "flags", false),
            new HookCheck("ai-walk-type", AI, "set", work, "walkType", false),
            new HookCheck("ai-state", AI, "set", work, "state", false),
            new HookCheck("ai-forward-direction", AI, "set", work, "captureDirection", false),
            new HookCheck("extrapolation-direction", AI, "setUsingExtrapolation", work, "captureDirection", false),
            new HookCheck("walk-prediction-length", AI, "setUsingWalkTowardState", work, "pathLengthForPrediction", false),
            new HookCheck("packet-walk-type", PACKET, "set", work, "walkType", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(AI)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(directionMethod("set", PACKET_ARGUMENT + ")V", true))
                .visit(directionMethod("setUsingExtrapolation", PACKET_ARGUMENT + "I)V", false))
                .visit(OptimizationVisitor.method("setUsingWalkTowardState", PACKET_ARGUMENT + ")V", PathVisitor::new)))
            .type(named(PACKET)).transform((dynamic, type, loader, module, domain) -> dynamic
            .visit(OptimizationVisitor.method("set", ZOMBIE_ARGUMENT + ")V",
                downstream -> new EnumVisitor(downstream, false)))
            .visit(Advice.to(PacketTiming.class).on(named("set").and(hasDescriptor(ZOMBIE_ARGUMENT + ")V")))));
    }

    private static AsmVisitorWrapper directionMethod(String name, String descriptor, boolean aiSet) {
        return OptimizationVisitor.method(name, descriptor, downstream -> new DirectionVisitor(downstream, aiSet))
            .writerFlags(ClassWriter.COMPUTE_FRAMES).readerFlags(ClassReader.SKIP_FRAMES);
    }

    public static final class PacketTiming {
        @Advice.OnMethodEnter
        public static long enter() {
            return GameServer.server ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) {
            if (started != 0L) {
                OptimizationTelemetry.Phase.PACKET_PREPARATION.recordSince(started);
            }
        }
    }

    private static class EnumVisitor extends MethodVisitor {
        private final boolean aiSet;
        private int walkTypes;
        private int states;

        EnumVisitor(MethodVisitor downstream, boolean aiSet) {
            super(ASM9, downstream);
            this.aiSet = aiSet;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKESTATIC && name.equals("fromString")
                    && descriptor.equals("(Ljava/lang/String;)L" + owner + ";")) {
                if (owner.equals(WALK_TYPE)) {
                    walkTypes++;
                    OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "walkType", descriptor);
                    return;
                }
                if (owner.equals(STATE)) {
                    states++;
                    OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "state", descriptor);
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("packet walk-type conversion", walkTypes, 1);
            OptimizationVisitor.requireMatches("packet state conversion", states, aiSet ? 1 : 0);
            super.visitEnd();
        }
    }

    private static final class DirectionVisitor extends MethodVisitor {
        // Both methods store the temporary Vector2 in slot 3. Vanilla uses no slots >= 20.
        private static final int VECTOR_SLOT = 3;
        private static final int SNAPSHOT_SLOT = 20;
        private final boolean aiSet;
        private int allocationStage;
        private int captures;
        private int xReads;
        private int yReads;
        private int flagReads;
        private boolean pendingDirectionRead;
        private boolean pendingShortRead;

        DirectionVisitor(MethodVisitor downstream, boolean aiSet) {
            super(ASM9, aiSet ? new EnumVisitor(downstream, true) : downstream);
            this.aiSet = aiSet;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == NEW && type.equals(VECTOR)) {
                allocationStage = 1;
                return;
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            if (allocationStage == 1 && opcode == DUP) {
                allocationStage = 2;
                return;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (allocationStage == 2 && opcode == INVOKESPECIAL && owner.equals(VECTOR)
                    && name.equals("<init>") && descriptor.equals("()V")) {
                allocationStage = 3;
                return;
            }
            if (allocationStage == 3 && opcode == INVOKEVIRTUAL && owner.equals("zombie/characters/IsoZombie")
                    && name.equals("getForwardDirection") && descriptor.equals("(L" + VECTOR + ";)L" + VECTOR + ";")) {
                OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "captureDirection", ZOMBIE_ARGUMENT + ")J");
                allocationStage = 4;
                captures++;
                return;
            }

            if (aiSet && opcode == INVOKESTATIC && owner.equals("zombie/characters/NetworkZombieVariables")
                    && name.equals("getBooleanVariables")
                    && descriptor.equals(ZOMBIE_ARGUMENT + ")Lzombie/util/flags/ShortFlags;")) {
                OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "flags", ZOMBIE_ARGUMENT + ")S");
                pendingShortRead = true;
                flagReads++;
                return;
            }
            if (pendingShortRead && opcode == INVOKEVIRTUAL && owner.equals("zombie/util/flags/ShortFlags")
                    && name.equals("asShort") && descriptor.equals("()S")) {
                pendingShortRead = false;
                return;
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            if (allocationStage == 4 && opcode == ASTORE && slot == VECTOR_SLOT) {
                mv.visitVarInsn(LSTORE, SNAPSHOT_SLOT);
                allocationStage = 5;
                return;
            }
            if (allocationStage == 5 && opcode == ALOAD && slot == VECTOR_SLOT) {
                pendingDirectionRead = true;
                return;
            }
            super.visitVarInsn(opcode, slot);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (maxLocals > SNAPSHOT_SLOT) {
                throw new IllegalStateException("Vanilla zombie prediction uses the snapshot slot");
            }
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (pendingDirectionRead && opcode == GETFIELD && owner.equals(VECTOR) && descriptor.equals("F")) {
                pendingDirectionRead = false;
                mv.visitVarInsn(LLOAD, SNAPSHOT_SLOT);
                switch (name) {
                    case "x" -> {
                        xReads++;
                        OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "directionX", "(J)F");
                    }
                    case "y" -> {
                        yReads++;
                        OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "directionY", "(J)F");
                    }
                    default -> throw new IllegalStateException("Unexpected zombie direction component " + name);
                }
                return;
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("packet direction capture", captures, 1);
            OptimizationVisitor.requireMatches("packet direction x read", xReads, 1);
            OptimizationVisitor.requireMatches("packet direction y read", yReads, 1);
            OptimizationVisitor.requireMatches("packet primitive flags", flagReads, aiSet ? 1 : 0);
            if (allocationStage != 5 || pendingDirectionRead || pendingShortRead) {
                throw new IllegalStateException("Incomplete zombie packet direction or flags transformation");
            }
            super.visitEnd();
        }
    }

    private static final class PathVisitor extends MethodVisitor {
        private int matches;

        PathVisitor(MethodVisitor downstream) {
            super(ASM9, downstream);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            if (opcode == INVOKEVIRTUAL && owner.equals("zombie/pathfind/PathFindBehavior2")
                    && name.equals("getPathLength") && descriptor.equals("()F")) {
                matches++;
                OptimizationVisitor.callStatic(mv, ZombiePacketWork.class, "pathLengthForPrediction",
                    "(Lzombie/pathfind/PathFindBehavior2;)F");
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitEnd() {
            OptimizationVisitor.requireMatches("zombie path prediction length", matches, 1);
            super.visitEnd();
        }
    }
}
