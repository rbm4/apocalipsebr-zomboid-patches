package cloud.apocalipse.pz.agent.features.npc;

import java.io.File;
import java.util.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcActorHookTest {
    @Test void transformedOriginalDefaultHitOwnerPreservesOrdinaryBehavior()throws Exception {
        var log=java.nio.file.Path.of("target/npc-default-hit.log");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin/java").toString(),
            "-Xverify:all","-cp",System.getProperty("java.class.path"),NpcFactoryFixtureMain.class.getName(),"hit-default")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),java.nio.file.Files.readString(log));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test void everyActorHookTransformsExactOriginalJarAndKeepsNativeStructure()throws Exception {
        var jar=new File(System.getProperty("pz.game.jar","Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"));
        try(var locator=new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(jar),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),ClassFileLocator.ForClassLoader.ofSystemLoader())){
            var pool=TypePool.Default.of(locator);var transformed=new HashMap<String,Map<String,Set<String>>>();
            var targets=new ArrayList<>(NpcModule.ACTOR_TARGETS);targets.add("zombie.network.GameServer");
            for(String target:targets){
                byte[] original=locator.locate(target).resolve();
                byte[] bytes=NpcModule.actorTransform(target,new ByteBuddy().redefine(pool.describe(target).resolve(),locator)).make().getBytes();
                assertEquals(members(original),members(bytes),target);
                transformed.put(target,calls(bytes));
            }
            var module=new NpcModule();
            for(var check:module.hookChecks()){
                var owner=transformed.get(check.targetClass());
                if(owner==null||check.method().equals("main"))continue;
                assertTrue(owner.getOrDefault(check.method(),Set.of()).contains(check.helperClass()+"."+check.helperMethod()),check.toString());
            }
        }
    }
    private static Set<String> members(byte[] bytes){
        var result=new HashSet<String>();new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public FieldVisitor visitField(int access,String name,String desc,String sig,Object value){result.add("F"+access+name+desc);return null;}
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){result.add("M"+access+name+desc);return null;}
        },ClassReader.SKIP_CODE);return result;
    }
    private static Map<String,Set<String>> calls(byte[] bytes){
        var result=new HashMap<String,Set<String>>();new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String desc,boolean itf){result.computeIfAbsent(name,k->new HashSet<>()).add(owner.replace('/','.')+"."+method);}
                };
            }
        },0);return result;
    }
}
