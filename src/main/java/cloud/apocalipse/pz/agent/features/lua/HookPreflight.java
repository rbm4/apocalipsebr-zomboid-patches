package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.HookCheck;
import java.util.*;
import java.util.function.BiFunction;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/** Inspect classpath bytes without defining native classes before the agent installs. */
public final class HookPreflight {
    private HookPreflight() { }
    public static void validate(List<String> targets,List<HookCheck> contracts,
            BiFunction<String,DynamicType.Builder<?>,DynamicType.Builder<?>> transform) {
        try(var locator=ClassFileLocator.ForClassLoader.ofSystemLoader()) {
            var pool=TypePool.Default.of(locator);
            for(String name:targets) {
                var original=pool.describe(name).resolve();
                byte[] bytes=transform.apply(name,new ByteBuddy().redefine(original,locator)).make().getBytes();
                Set<String> calls=new HashSet<>();
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode,String owner,String target,String desc,boolean itf) {
                                calls.add(method+":"+owner.replace('/','.')+":"+target);
                            }
                        };
                    }
                },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
                for(var contract:contracts) if(contract.targetClass().equals(name)
                        && !calls.contains(contract.method()+":"+contract.helperClass()+":"+contract.helperMethod()))
                    throw new IllegalStateException("Missing original-game hook: "+contract.id());
            }
        }catch(Exception|LinkageError failure) {
            throw new IllegalStateException("Original-game hook preflight failed",failure);
        }
    }
}
