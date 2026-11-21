package cloud.apocalipse.pz.agent.features.lua.login;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** One drain on the original always-running server loop, including empty paused servers. */
public final class AsyncLoopVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public int mergeWriter(int flags){return flags|ClassWriter.COMPUTE_MAXS;}
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader) {
        return new ClassVisitor(Opcodes.ASM9,visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                MethodVisitor delegate=super.visitMethod(access,name,descriptor,signature,exceptions);
                if(!name.equals("main")||!descriptor.equals("([Ljava/lang/String;)V"))return delegate;
                return new MethodVisitor(Opcodes.ASM9,delegate) {
                    @Override public void visitMethodInsn(int opcode,String owner,String target,String desc,boolean itf) {
                        if(owner.equals("zombie/network/LoginQueue")&&target.equals("update")&&desc.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"cloud/apocalipse/pz/agent/features/lua/login/AsyncRuntime","update","()V",false);anchors++;
                        }
                        super.visitMethodInsn(opcode,owner,target,desc,itf);
                    }
                };
            }
            @Override public void visitEnd(){if(anchors!=1)throw new IllegalStateException("Account continuation loop anchor changed");super.visitEnd();}
        };
    }
}
