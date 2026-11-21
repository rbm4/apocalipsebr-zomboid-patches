package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Exact original 42.22.2 simulation anchor, plus cleanup before System.exit bypasses method exit. */
public final class NpcServerLoopVisitor extends AsmVisitorWrapper.AbstractBase {
    static final String RUNTIME = "cloud/apocalipse/pz/agent/features/npc/NpcRuntime";
    @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_MAXS; }

    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor,
            Implementation.Context context, TypePool pool, FieldList<FieldDescription.InDefinedShape> fields,
            MethodList<?> methods, int writerFlags, int readerFlags) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            boolean main;
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("main") || !descriptor.equals("([Ljava/lang/String;)V")) return original;
                main = true;
                return new MethodVisitor(Opcodes.ASM9, original) {
                    int ticks, exits;
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("java/lang/System")
                                && name.equals("exit") && desc.equals("(I)V")) {
                            exits++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "shutdown", "()V", false);
                        }
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals("zombie/network/LoginQueue")
                                && name.equals("update") && desc.equals("()V")) {
                            ticks++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "update", "()V", false);
                        }
                    }
                    @Override public void visitEnd() {
                        if (ticks != 1 || exits < 1) throw new IllegalStateException("NPC original GameServer.main anchors mismatch: ticks=" + ticks + ", exits=" + exits);
                        super.visitEnd();
                    }
                };
            }
            @Override public void visitEnd() {
                if (!main) throw new IllegalStateException("NPC GameServer.main missing");
                super.visitEnd();
            }
        };
    }
}
