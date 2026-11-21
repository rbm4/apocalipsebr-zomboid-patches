package cloud.apocalipse.pz.agent.features.lua.login;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.*;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Original account algorithms use detached worker views; server-thread calls retain original reads. */
public final class WorkerBoundaryVisitor extends AsmVisitorWrapper.AbstractBase {
    private static final String VIEW="cloud/apocalipse/pz/agent/features/lua/login/WorkerContext";
    @Override public int mergeWriter(int flags){return flags|ClassWriter.COMPUTE_MAXS;}
    @Override public ClassVisitor wrap(TypeDescription type,ClassVisitor visitor,Implementation.Context context,TypePool pool,
            FieldList<FieldDescription.InDefinedShape> fields,MethodList<?> methods,int writer,int reader) {
        return new ClassVisitor(Opcodes.ASM9,visitor) {
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,super.visitMethod(access,name,descriptor,signature,exceptions)) {
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                        if(opcode==Opcodes.INVOKESTATIC && owner.equals("zombie/characters/Roles")
                                && java.util.Set.of("getRoleById","getDefaultForBanned","getDefaultForUser","getDefaultForNewUser").contains(method)) {
                            super.visitMethodInsn(opcode,VIEW,method,desc,false);return;
                        }
                        if(opcode==Opcodes.INVOKESTATIC && owner.equals("zombie/core/znet/SteamUtils") && method.equals("isSteamModeEnabled")) {
                            super.visitMethodInsn(opcode,VIEW,"steam",desc,false);return;
                        }
                        if(opcode==Opcodes.INVOKESTATIC && owner.equals("zombie/network/ServerWorldDatabase") && method.equals("isValidUserName")) {
                            super.visitMethodInsn(opcode,VIEW,"validName",desc,false);return;
                        }
                        if(method.equals("getValue") && owner.equals("zombie/network/ServerOptions$BooleanServerOption")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"booleanOption","(Ljava/lang/Object;)Z",false);return;
                        }
                        if(method.equals("getValue") && owner.equals("zombie/network/ServerOptions$IntegerServerOption")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"integerOption","(Ljava/lang/Object;)I",false);return;
                        }
                        if(type.getName().equals("zombie.network.packets.service.RequestUserLogPacket")
                                && owner.equals("zombie/network/ServerWorldDatabase") && method.equals("getUserlog")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"cloud/apocalipse/pz/agent/features/lua/login/PacketRuntime",
                                "userlog","(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/ArrayList;",false);return;
                        }
                        super.visitMethodInsn(opcode,owner,method,desc,itf);
                    }
                    @Override public void visitFieldInsn(int opcode,String owner,String name,String desc) {
                        if(opcode==Opcodes.GETSTATIC && owner.equals("zombie/core/Core") && name.equals("gameSaveWorld")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"world","()Ljava/lang/String;",false);return;
                        }
                        if(opcode==Opcodes.GETFIELD && owner.equals("zombie/network/ServerWorldDatabase") && name.equals("commandLineAdminUsername")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"admin","(Ljava/lang/Object;)Ljava/lang/String;",false);return;
                        }
                        super.visitFieldInsn(opcode,owner,name,desc);
                        if(opcode==Opcodes.GETSTATIC && owner.equals("zombie/network/ServerWorldDatabase")) {
                            if(name.equals("dateFormat"))super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"dateFormat","(Ljava/text/DateFormat;)Ljava/text/DateFormat;",false);
                            if(name.equals("asciiEncoder"))super.visitMethodInsn(Opcodes.INVOKESTATIC,VIEW,"ascii","(Ljava/nio/charset/CharsetEncoder;)Ljava/nio/charset/CharsetEncoder;",false);
                        }
                    }
                };
            }
        };
    }
}
