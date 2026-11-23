package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Original 42.22.2 simulation and owner-thread process exit call sites. */
public final class NpcServerLoopVisitor extends AsmVisitorWrapper.AbstractBase {
    static final String RUNTIME = "cloud/apocalipse/pz/agent/features/npc/NpcRuntime";
    private final boolean serverMain;

    NpcServerLoopVisitor(boolean serverMain) { this.serverMain = serverMain; }

    @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_MAXS; }

    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor,
            Implementation.Context context, TypePool pool, FieldList<FieldDescription.InDefinedShape> fields,
            MethodList<?> methods, int writerFlags, int readerFlags) {
        String method = serverMain ? "main" : "preupdate";
        String signatureDescriptor = serverMain ? "([Ljava/lang/String;)V" : "()V";
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            boolean found;
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals(method) || !descriptor.equals(signatureDescriptor)) return original;
                found = true;
                return new MethodVisitor(Opcodes.ASM9, original) {
                    int ticks, exits;
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                                && name.equals("exit") && desc.equals("(I)V")) {
                            exits++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "shutdown", "()V", false);
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                        if (serverMain && opcode == Opcodes.INVOKESTATIC && owner.equals("zombie/network/LoginQueue")
                                && name.equals("update") && desc.equals("()V")) {
                            ticks++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "update", "()V", false);
                        }
                    }
                    @Override public void visitEnd() {
                        if (serverMain ? ticks != 1 || exits < 1 : exits != 1) {
                            throw new IllegalStateException("NPC " + method + " anchors mismatch: ticks=" + ticks + ", exits=" + exits);
                        }
                        super.visitEnd();
                    }
                };
            }
            @Override public void visitEnd() {
                if (!found) throw new IllegalStateException("NPC shutdown boundary missing: " + method);
                super.visitEnd();
            }
        };
    }
}
