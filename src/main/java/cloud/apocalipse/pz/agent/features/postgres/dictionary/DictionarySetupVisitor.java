package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Insert inside vanilla init's error handling, after successful save and before backup deletion. */
final class DictionarySetupVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_MAXS; }
    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor, Implementation.Context context,
            TypePool pool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int writer, int reader) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("init") || !descriptor.equals("()V") || (access & Opcodes.ACC_STATIC) == 0) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean face) {
                        boolean anchor = opcode == Opcodes.INVOKEVIRTUAL && owner.equals("zombie/world/DictionaryData")
                            && method.equals("save") && desc.equals("()V");
                        if (anchor) super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(opcode, owner, method, desc, face);
                        if (anchor) {
                            anchors++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "cloud/apocalipse/pz/agent/features/postgres/dictionary/DictionaryRuntime",
                                "afterRebuild", "(Ljava/lang/Object;)V", false);
                        }
                    }
                };
            }
            @Override public void visitEnd() {
                if (anchors != 1) throw new IllegalStateException("Expected one original WorldDictionary.init save boundary; found " + anchors);
                super.visitEnd();
            }
        };
    }
}
