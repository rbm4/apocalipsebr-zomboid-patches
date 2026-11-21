package cloud.apocalipse.pz.agent.features.lua;

import java.io.File;
import java.util.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.jar.asm.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LuaHookTest {
    @Test void everyDeclaredHookTransformsTheOriginalArchiveWithoutChangingMembers() throws Exception {
        String path=System.getProperty("pz.game.jar","Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar");
        assertTrue(new File(path).isFile(),"Original 42.22.2 archive required");
        try(var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(new File(path)),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            var pool=TypePool.Default.of(locator);
            var hooks=new ArrayList<>(new LuaModule().hookChecks());
            hooks.addAll(new cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule().hookChecks());
            var targets=new java.util.LinkedHashSet<>(LuaModule.TARGETS);
            targets.addAll(cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule.TARGETS);
            for(String name:targets) {
                byte[] original=locator.locate(name).resolve();
                net.bytebuddy.dynamic.DynamicType.Builder<?> builder=new ByteBuddy().redefine(pool.describe(name).resolve(),locator);
                if(LuaModule.TARGETS.contains(name)) builder=LuaModule.transform(name,builder);
                if(cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule.TARGETS.contains(name))
                    builder=cloud.apocalipse.pz.agent.features.lua.login.AsyncAccountsModule.transform(name,builder);
                byte[] transformed=builder.make().getBytes();
                assertEquals(members(original),members(transformed),name);
                Set<String> calls=calls(transformed);
                for(var check:hooks) if(check.targetClass().equals(name))
                    assertTrue(calls.contains(check.method()+":"+check.helperClass()+":"+check.helperMethod()),check.toString());
            }
        }
    }
    private static Set<String> calls(byte[] bytes) {
        Set<String> values=new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode,String owner,String target,String desc,boolean itf) {
                        values.add(name+":"+owner.replace('/','.')+":"+target);
                    }
                };
            }
        },0);return values;
    }
    private static Set<String> members(byte[] bytes) {
        Set<String> values=new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access,String name,String desc,String signature,Object value) {
                values.add("F"+access+name+desc);return null;
            }
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                values.add("M"+access+name+desc);return null;
            }
        },ClassReader.SKIP_CODE);return values;
    }
}
