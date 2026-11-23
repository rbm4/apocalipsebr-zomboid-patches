package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcHookTest {
    @Test void registryConfigurationAndDisabledBehavior() {
        assertTrue(AgentModules.all().stream().anyMatch(NpcModule.class::isInstance));
        String previous = System.getProperty(AgentConfiguration.NPC_ENABLED_PROPERTY);
        try {
            System.setProperty(AgentConfiguration.NPC_ENABLED_PROPERTY,"false");
            assertFalse(AgentConfiguration.npcSettings().enabled());
            var module = new NpcModule(); module.initialize();
            assertNotNull(module.disabledReason());
            var builder = new net.bytebuddy.agent.builder.AgentBuilder.Default();
            assertSame(builder,module.configure(builder));
            assertTrue(module.hookChecks().size()>=3);
        } finally {
            if (previous == null) System.clearProperty(AgentConfiguration.NPC_ENABLED_PROPERTY);
            else System.setProperty(AgentConfiguration.NPC_ENABLED_PROPERTY,previous);
        }
    }

    @Test void transformedNativeEntryPointsReachProductionRuntimeAndPreserveControlFlow() throws Exception {
        for (String scenario : List.of("normal","quit","exception","factory","console")) {
            Path log = Path.of("target", "npc-hook-"+scenario+".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xverify:all", "-cp", System.getProperty("java.class.path"),
                scenario.equals("factory")?NpcFactoryFixtureMain.class.getName():NpcHookSmokeMain.class.getName(), scenario)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Hook fixture timed out");
                assertEquals(0,process.exitValue(),Files.readString(log));
                assertTrue(Files.readString(log).contains("NPC transformed lifecycle passed"));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }

    @Test void exactOriginalJarTransformsWithoutStructuralChangesAndUsesDeclaredHooks() throws Exception {
        File jar = new File(System.getProperty("pz.game.jar",
            "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar"));
        assertTrue(jar.isFile(),"Original game archive required: set -Dpz.game.jar");
        try (var locator = new ClassFileLocator.Compound(ClassFileLocator.ForJarFile.of(jar),
                ClassFileLocator.ForClassLoader.ofPlatformLoader(),ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            var pool = TypePool.Default.of(locator);
            for (String name : List.of("zombie.network.GameServer","zombie.network.ServerMap")) {
                byte[] original = locator.locate(name).resolve();
                byte[] transformed = NpcModule.transform(name,new ByteBuddy().redefine(pool.describe(name).resolve(),locator)).make().getBytes();
                assertEquals(members(original),members(transformed));
                var calls = calls(transformed);
                assertTrue(calls.contains("shutdown"));
                if (name.endsWith("GameServer")) {
                    assertEquals(1,Collections.frequency(calls,"update"));
                    assertTrue(Collections.frequency(calls,"shutdown")>=3,"Exit and exceptional fallback cleanup required");
                }
            }
            var server = pool.describe("zombie.network.GameServer").resolve();
            for (String name : List.of("server","udpEngine"))
                assertEquals(1,server.getDeclaredFields().filter(net.bytebuddy.matcher.ElementMatchers.named(name)
                    .and(net.bytebuddy.matcher.ElementMatchers.isPublic())).size());
            var world = pool.describe("zombie.iso.IsoWorld").resolve();
            for (String name : List.of("instance","currentCell"))
                assertEquals(1,world.getDeclaredFields().filter(net.bytebuddy.matcher.ElementMatchers.named(name)
                    .and(net.bytebuddy.matcher.ElementMatchers.isPublic())).size());
        }
    }

    @Test void unsupportedLoopFailsTransformationInsteadOfPretendingPortIsInstalled() {
        assertThrows(IllegalStateException.class, () -> NpcModule.transform("zombie.network.GameServer",
            new ByteBuddy().redefine(MissingAnchor.class)).make());
    }

    public static class MissingAnchor { public static void main(String[] args) { } }

    private static Set<String> members(byte[] bytes) {
        Set<String> result = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access,String name,String desc,String sig,Object value) {
                result.add("F:"+access+":"+name+desc); return null;
            }
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex) {
                result.add("M:"+access+":"+name+desc); return null;
            }
        },ClassReader.SKIP_CODE);
        return result;
    }
    private static List<String> calls(byte[] bytes) {
        List<String> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op,String owner,String method,String desc,boolean itf) {
                        if (owner.equals(NpcServerLoopVisitor.RUNTIME)) result.add(method);
                    }
                };
            }
        },0);
        return result;
    }
}
