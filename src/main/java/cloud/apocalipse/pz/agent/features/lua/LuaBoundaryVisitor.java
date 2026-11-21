package cloud.apocalipse.pz.agent.features.lua;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Exact original 42.22.2 invocation anchors; missing anchors reject transformation. */
public final class LuaBoundaryVisitor extends AsmVisitorWrapper.AbstractBase {
    private final String target;
    public LuaBoundaryVisitor(String target) { this.target=target; }
    private static final String HELPER="cloud/apocalipse/pz/agent/features/lua/LuaRuntime";
    @Override public int mergeWriter(int flags) { return flags|ClassWriter.COMPUTE_FRAMES; }
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,
            TypePool pool,FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader) {
        return new ClassVisitor(Opcodes.ASM9,visitor) {
            int anchors;
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                MethodVisitor delegate=super.visitMethod(access,name,descriptor,signature,exceptions);
                boolean loop=target.equals("zombie.network.GameServer") && name.equals("main") && descriptor.equals("([Ljava/lang/String;)V");
                boolean areas=target.equals("zombie.popman.LoadedAreas") && name.equals("set") && descriptor.equals("()Z");
                boolean generator=target.equals("zombie.iso.objects.IsoGenerator") && name.equals("update") && descriptor.equals("()V");
                if (!loop && !areas && !generator) return delegate;
                return new MethodVisitor(Opcodes.ASM9,delegate) {
                    int clocks;
                    final Label skipDepletion=new Label();
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                        if (loop && owner.equals("zombie/network/LoginQueue") && method.equals("update") && desc.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"update","()V",false); anchors++;
                        }
                        if (areas && owner.equals("zombie/popman/LoadedAreas") && method.equals("compareWithPrev") && desc.equals("()Z")) {
                            // The receiver for compareWithPrev is already on the stack.
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"populationArea","(Ljava/lang/Object;)V",false); anchors++;
                        }
                        if(generator && owner.equals("zombie/GameTime") && method.equals("getInstance") && desc.equals("()Lzombie/GameTime;")) {
                            clocks++;
                            if(clocks==2) {
                                Label nativeDepletion=new Label();
                                super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"skipGeneratorDepletion","(Ljava/lang/Object;)Z",false);
                                super.visitJumpInsn(Opcodes.IFEQ,nativeDepletion);
                                // Vanilla has the receiver of lastHour's PUTFIELD on the stack at the target.
                                super.visitVarInsn(Opcodes.ALOAD,0);
                                super.visitJumpInsn(Opcodes.GOTO,skipDepletion);
                                super.visitLabel(nativeDepletion); anchors++;
                            }
                            if(clocks==3)super.visitLabel(skipDepletion);
                        }
                        super.visitMethodInsn(opcode,owner,method,desc,itf);
                    }
                    @Override public void visitEnd() {
                        if(generator && clocks!=3)throw new IllegalStateException("Generator depletion anchors changed");
                        super.visitEnd();
                    }
                };
            }
            @Override public void visitEnd() {
                if (anchors!=1) throw new IllegalStateException("Lua subsystem boundary changed: "+type.getName());
                super.visitEnd();
            }
        };
    }
}
