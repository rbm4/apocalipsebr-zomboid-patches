package cloud.apocalipse.pz.agent.features.telemetry;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** Instruments only the validated 42.22.2 GameServer.main loop boundaries. */
public final class TelemetryServerLoopVisitor extends AsmVisitorWrapper.AbstractBase {
    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String WORLD_MAP_OWNER = "zombie/worldMap/WorldMapVisitedServer";
    private static final String LEGACY_TELEMETRY_OWNER = "zombie/ApocBRServerTelemetryLite";
    private static final String METRICS_OWNER = "cloud/apocalipse/pz/agent/features/telemetry/TelemetryServerLoopMetrics";
    private static final String NANO_TIME_DESCRIPTOR = "()J";
    private static final String DURATION_DESCRIPTOR = "(J)V";

    @Override
    public int mergeWriter(int flags) {
        return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public ClassVisitor wrap(
            TypeDescription instrumentedType,
            ClassVisitor classVisitor,
            Implementation.Context implementationContext,
            TypePool typePool,
            FieldList<FieldDescription.InDefinedShape> fields,
            MethodList<?> methods,
            int writerFlags,
            int readerFlags) {
        String gameServerOwner = instrumentedType.getInternalName();
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            private boolean foundMain;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"main".equals(name) || !"([Ljava/lang/String;)V".equals(descriptor)) {
                    return visitor;
                }

                this.foundMain = true;
                return new LoopMethodVisitor(visitor, gameServerOwner);
            }

            @Override
            public void visitEnd() {
                super.visitEnd();
                if (!this.foundMain) {
                    TelemetryServerLoopMetrics.configure(false, false, false);
                    System.err.println(
                        "[ApocBR-Agent][Telemetry] GameServer.main was not found; server-loop telemetry is disabled."
                    );
                }
            }
        };
    }

    private static final class LoopMethodVisitor extends MethodVisitor {
        private final String gameServerOwner;
        private Label lastLabel;
        private Label pendingLoopHead;
        private Label loopHead;
        private boolean pendingDoneRead;
        private boolean startInserted;
        private int startAnchors;
        private int tickAnchors;
        private int loopBackEdges;
        private int legacyTickAnchors;
        private int legacyLoopAnchors;

        LoopMethodVisitor(MethodVisitor visitor, String gameServerOwner) {
            super(Opcodes.ASM9, visitor);
            this.gameServerOwner = gameServerOwner;
        }

        @Override
        public void visitLabel(Label label) {
            this.lastLabel = label;
            super.visitLabel(label);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            this.pendingDoneRead = opcode == Opcodes.GETSTATIC
                && this.gameServerOwner.equals(owner)
                && "done".equals(name)
                && "Z".equals(descriptor);
            this.pendingLoopHead = this.pendingDoneRead ? this.lastLabel : null;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (this.pendingDoneRead && (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE)) {
                if (this.loopHead == null) {
                    this.loopHead = this.pendingLoopHead;
                }
            }
            this.pendingDoneRead = false;
            this.pendingLoopHead = null;

            if (opcode == Opcodes.GOTO && label == this.loopHead) {
                this.loopBackEdges++;
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    METRICS_OWNER,
                    "finishVanillaIteration",
                    "()V",
                    false
                );
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (this.loopHead != null && !this.startInserted
                    && Opcodes.INVOKESTATIC == opcode
                    && SYSTEM_OWNER.equals(owner)
                    && "nanoTime".equals(name)
                    && NANO_TIME_DESCRIPTOR.equals(descriptor)) {
                this.startAnchors++;
                this.startInserted = true;
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                super.visitInsn(Opcodes.DUP2);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    METRICS_OWNER,
                    "beginIteration",
                    DURATION_DESCRIPTOR,
                    false
                );
                return;
            }

            if (WORLD_MAP_OWNER.equals(owner) && "update".equals(name) && "()V".equals(descriptor)) {
                this.tickAnchors++;
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    METRICS_OWNER,
                    "recordVanillaTick",
                    "()V",
                    false
                );
                return;
            }

            if (LEGACY_TELEMETRY_OWNER.equals(owner) && "recordTick".equals(name)
                    && DURATION_DESCRIPTOR.equals(descriptor)) {
                this.legacyTickAnchors++;
                super.visitInsn(Opcodes.DUP2);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    METRICS_OWNER,
                    "recordLegacyTick",
                    DURATION_DESCRIPTOR,
                    false
                );
            } else if (LEGACY_TELEMETRY_OWNER.equals(owner) && "recordLoop".equals(name)
                    && DURATION_DESCRIPTOR.equals(descriptor)) {
                this.legacyLoopAnchors++;
                super.visitInsn(Opcodes.DUP2);
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    METRICS_OWNER,
                    "finishLegacyIteration",
                    DURATION_DESCRIPTOR,
                    false
                );
            }

            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            boolean anchorsValid = this.loopHead != null
                && this.startAnchors == 1
                && this.tickAnchors == 1
                && this.loopBackEdges > 0
                && this.legacyTickAnchors <= 1
                && this.legacyLoopAnchors <= 1;
            TelemetryServerLoopMetrics.configure(
                anchorsValid,
                this.legacyTickAnchors == 1,
                this.legacyLoopAnchors == 1
            );
            if (anchorsValid) {
                System.out.println(
                    "[ApocBR-Agent][Telemetry] GameServer loop anchors verified; "
                        + "legacy tick="
                        + (this.legacyTickAnchors == 1)
                        + ", legacy loop="
                        + (this.legacyLoopAnchors == 1)
                        + "."
                );
            } else {
                System.err.println(
                    "[ApocBR-Agent][Telemetry] GameServer loop anchors did not match 42.22.2; "
                        + "server-loop telemetry is disabled."
                );
            }
        }

    }
}
