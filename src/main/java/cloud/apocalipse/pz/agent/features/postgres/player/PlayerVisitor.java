package cloud.apocalipse.pz.agent.features.postgres.player;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Narrow original call-site anchors; never duplicates the native acceptance or creation method. */
final class PlayerVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS; }
    @Override public int mergeReader(int flags) { return flags | ClassReader.EXPAND_FRAMES; }
    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor, Implementation.Context context, TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int writer, int reader) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean loop = type.getName().equals("zombie.network.GameServer") && name.equals("main") && descriptor.equals("([Ljava/lang/String;)V");
                boolean creation = type.getName().endsWith("CreatePlayerPacket") && name.equals("processServer");
                boolean updates = type.getName().equals("zombie.network.NetworkPlayerManager") && name.equals("update") && descriptor.equals("()V");
                if (!loop && !creation && !updates) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        if (loop && owner.equals("zombie/network/LoginQueue") && called.equals("update") && desc.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "cloud/apocalipse/pz/agent/features/postgres/player/PlayerRuntime", "update", "()V", false); anchors++;
                        }
                        super.visitMethodInsn(opcode, owner, called, desc, itf);
                        if (creation && owner.equals("zombie/savefile/ServerPlayerDB") && called.equals("process") && desc.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "cloud/apocalipse/pz/agent/features/postgres/player/PlayerRuntime", "afterNativeCreate", "()Z", false);
                            var continuation = new Label(); super.visitJumpInsn(Opcodes.IFEQ, continuation); super.visitInsn(Opcodes.RETURN); super.visitLabel(continuation); anchors++;
                        }
                    }
                    @Override public void visitTypeInsn(int opcode, String name) {
                        super.visitTypeInsn(opcode, name);
                        if (updates && opcode == Opcodes.CHECKCAST && name.equals("zombie/characters/IsoPlayer")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "cloud/apocalipse/pz/agent/features/postgres/player/PlayerMutations", "observe", "(Lzombie/characters/IsoPlayer;)V", false); anchors++;
                        }
                    }
                };
            }
            @Override public void visitEnd() { if (anchors != 1) throw new IllegalStateException("Original player anchor changed: " + type.getName()); super.visitEnd(); }
        };
    }
}
