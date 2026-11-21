package cloud.apocalipse.pz.agent.features.lua;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Append custom enum registration after all vanilla registrations, before exposing the collected types. */
public final class LuaExposureVisitor extends AsmVisitorWrapper.AbstractBase {
    @Override public int mergeWriter(int flags){return flags|ClassWriter.COMPUTE_MAXS;}
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader) {
        return new ClassVisitor(Opcodes.ASM9,visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                MethodVisitor delegate=super.visitMethod(access,name,descriptor,signature,exceptions);
                if(!name.equals("exposeAll")||!descriptor.equals("()V"))return delegate;
                return new MethodVisitor(Opcodes.ASM9,delegate) {
                    @Override public void visitFieldInsn(int opcode,String owner,String field,String desc) {
                        if(opcode==Opcodes.GETFIELD && owner.equals("zombie/Lua/LuaManager$Exposer")
                                && field.equals("exposed") && desc.equals("Ljava/util/HashSet;")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"cloud/apocalipse/pz/agent/features/lua/LuaRuntime",
                                "expose","(Ljava/lang/Object;)V",false);anchors++;
                        }
                        super.visitFieldInsn(opcode,owner,field,desc);
                    }
                };
            }
            @Override public void visitEnd(){if(anchors!=1)throw new IllegalStateException("Lua exposure iteration boundary changed");super.visitEnd();}
        };
    }
}
