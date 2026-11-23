package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Wrap at original field initialization, before the constructor publishes its cell or starts workers. */
public final class NpcCellVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader){
        return new ClassVisitor(Opcodes.ASM9,visitor){
            int assignments;
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                var delegate=super.visitMethod(access,name,desc,sig,ex);if(!name.equals("<init>"))return delegate;
                return new MethodVisitor(Opcodes.ASM9,delegate){
                    @Override public void visitFieldInsn(int op,String owner,String field,String descriptor){
                        if(op==Opcodes.PUTFIELD&&owner.equals(type.getInternalName())&&field.equals("objectList")&&descriptor.equals("Ljava/util/Set;")){
                            assignments++;super.visitMethodInsn(Opcodes.INVOKESTATIC,"cloud/apocalipse/pz/agent/features/npc/NpcMembershipSet","wrap","(Ljava/util/Set;)Ljava/util/Set;",false);
                        }
                        super.visitFieldInsn(op,owner,field,descriptor);
                    }
                };
            }
            @Override public void visitEnd(){if(assignments!=1)throw new IllegalStateException("NPC original IsoCell membership initialization mismatch: "+assignments);super.visitEnd();}
        };
    }
}
