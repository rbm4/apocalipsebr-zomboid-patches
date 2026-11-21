package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Tag only a newly allocated nested payload; never relabel retained byteData on item reuse. */
final class ItemPayloadVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public int mergeWriter(int flags) { return flags | ClassWriter.COMPUTE_MAXS; }
    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor, Implementation.Context context, TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int writer, int reader) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("load") || !desc.equals("(Ljava/nio/ByteBuffer;I)V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                        if (opcode == Opcodes.PUTFIELD && owner.equals("zombie/inventory/InventoryItem") && field.equals("byteData") && descriptor.equals("Ljava/nio/ByteBuffer;")) {
                            super.visitInsn(Opcodes.DUP); super.visitInsn(Opcodes.ACONST_NULL);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "cloud/apocalipse/pz/agent/features/postgres/dictionary/DictionaryDecode", "inherit", "(Ljava/lang/Object;Ljava/lang/Integer;)V", false); anchors++;
                        }
                        super.visitFieldInsn(opcode, owner, field, descriptor);
                    }
                };
            }
            @Override public void visitEnd() { if (anchors != 1) throw new IllegalStateException("Original nested item payload anchor changed"); super.visitEnd(); }
        };
    }
}
