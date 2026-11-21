package cloud.apocalipse.pz.agent.features.postgres;

import java.util.Set;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

/** Redirects scoped native account reads without assigning a shared Connection to the game singleton. */
final class AccountConnectionVisitor extends AsmVisitorWrapper.AbstractBase {
    static final String[] CALLS = {
        "getWhitelistUsers", "getUserlogUsers", "getUserCounter", "containsUser", "containsCaseinsensitiveUser",
        "changeUsername", "isSteamIDinWhitelist", "isSteamIDAllowed", "addSteamID", "removeSteamID", "addUser",
        "updateDisplayName", "getDisplayName", "removeUser", "removeWholeUserLog", "removeUserLog", "saveRole",
        "removeRole", "saveDefaultRole", "getDefaultRoleId", "saveRoleCapabilities", "loadRoles", "googleAuthClient",
        "authClient", "authOwner", "isNewAccountAllowed", "changePassword", "changePwd", "setRole", "getUserlog",
        "addUserlog", "getUserRoleNameByUsername", "banUser", "getFirstBannedIPForUser", "banIp", "isSteamIdBanned",
        "banSteamID", "setUserSteamID", "setPassword", "getUserGoogleKey", "setUserGoogleKey", "resetUserGoogleKey",
        "updateLastConnectionDate", "addWarningPoint", "addTicket", "viewedTicket", "getBannedIPs", "getBannedSteamIDs",
        "getTickets", "getAnswer", "removeTicket"
    };
    private static final Set<String> SCOPED = Set.of(CALLS);
    private static final Set<String> LIFECYCLE = Set.of("create", "connect", "close");
    private static final String OWNER = "zombie/network/ServerWorldDatabase";
    private static final String RUNTIME = "cloud/apocalipse/pz/agent/features/postgres/PostgresRuntime";

    @Override public ClassVisitor wrap(TypeDescription type, ClassVisitor visitor,
            Implementation.Context context, TypePool pool, FieldList<FieldDescription.InDefinedShape> fields,
            MethodList<?> methods, int writerFlags, int readerFlags) {
        return new ClassVisitor(Opcodes.ASM9, visitor) {
            private int reads;
            private boolean create, connect, close;
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if ("()V".equals(descriptor)) {
                    create |= "create".equals(name); connect |= "connect".equals(name); close |= "close".equals(name);
                }
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access,name,descriptor,signature,exceptions)) {
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        if (OWNER.equals(owner) && "conn".equals(field) && "Ljava/sql/Connection;".equals(desc)
                                && !LIFECYCLE.contains(name)) {
                            if (opcode != Opcodes.GETFIELD || !SCOPED.contains(name)) {
                                throw new IllegalStateException("Uncovered ServerWorldDatabase connection access in " + name);
                            }
                            reads++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,RUNTIME,"connection",
                                "(Ljava/lang/Object;)Ljava/sql/Connection;",false);
                            return;
                        }
                        super.visitFieldInsn(opcode,owner,field,desc);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if ("addUser".equals(name) && opcode == Opcodes.INVOKESTATIC
                                && "zombie/savefile/ServerPlayerDB".equals(owner) && "getInstance".equals(method)
                                && "()Lzombie/savefile/ServerPlayerDB;".equals(desc)) {
                            // Its only use here is the redirected Steam-ID lookup below. Avoid constructing a SQLite player DB.
                            super.visitInsn(Opcodes.ACONST_NULL);
                            return;
                        }
                        if ("addUser".equals(name) && "zombie/savefile/ServerPlayerDB".equals(owner)
                                && "getNetworkUserSteamID".equals(method)
                                && "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(desc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,RUNTIME,"networkUserSteamId",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",false);
                            return;
                        }
                        super.visitMethodInsn(opcode,owner,method,desc,itf);
                    }
                };
            }
            @Override public void visitEnd() {
                if (!create || !connect || !close || reads == 0) {
                    throw new IllegalStateException("ServerWorldDatabase does not match the account hook contract");
                }
                super.visitEnd();
            }
        };
    }
}
