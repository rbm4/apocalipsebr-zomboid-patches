package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.experimental.luapreparation.AsyncLuaPreparationModule;
import cloud.apocalipse.pz.agent.features.modactivation.ClientModActivationModule;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AsyncLuaPreparationHookTest {
    @TempDir Path directory;

    @Test void enabledHooksTransformOriginalJarAlongsideClientModActivation() throws Exception {
        assertTrue(AgentModules.all().stream().anyMatch(AsyncLuaPreparationModule.class::isInstance));
        var module = new AsyncLuaPreparationModule();
        var builder = new AgentBuilder.Default().disableClassFormatChanges();
        String property = AgentConfiguration.ASYNC_LUA_PREPARATION_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            module.initialize();
            assertNotNull(module.disabledReason());
            assertSame(builder, module.configure(builder));
            System.setProperty(property, "true");
            module.initialize();
            assertNull(module.disabledReason());

            var modActivation = new ClientModActivationModule();
            var transformer = module.configure(modActivation.configure(builder)).makeRaw();
            var checks = new java.util.ArrayList<>(module.hookChecks());
            checks.addAll(modActivation.hookChecks());
            try (var locator = ClassFileLocator.ForJarFile.of(originalJar().toFile())) {
                for (String target : checks.stream().map(HookCheck::targetClass).distinct().toList()) {
                    byte[] transformed = transformer.transform(getClass().getClassLoader(), target.replace('.', '/'),
                        null, null, locator.locate(target).resolve());
                    assertNotNull(transformed, target);
                    var calls = helperCalls(transformed);
                    for (HookCheck check : checks) {
                        if (target.equals(check.targetClass())) {
                            assertTrue(calls.contains(check.method() + ":" + check.helperClass() + ":" + check.helperMethod()),
                                check.id());
                        }
                    }
                }
            }
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test void preparedReaderAndFallbackExecuteAgainstOriginalJar() throws Exception {
        Path jar = directory.resolve("lua-preparation-fixture-agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", AsyncLuaPreparationSmokeMain.class.getName());
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }

        Path log = directory.resolve("client.log");
        String classpath = originalJar() + File.pathSeparator + System.getProperty("java.class.path");
        Process client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-D" + AgentConfiguration.ASYNC_LUA_PREPARATION_PROPERTY + "=true", "-javaagent:" + jar,
            "-cp", classpath, AsyncLuaPreparationSmokeMain.class.getName())
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(client.waitFor(25, TimeUnit.SECONDS), "preparation fixture did not exit");
            String output = Files.readString(log);
            assertEquals(0, client.exitValue(), output);
            assertTrue(output.contains("Client Lua preparation smoke passed"), output);
        } finally {
            if (client.isAlive()) client.destroyForcibly();
        }
    }

    private static Path originalJar() throws Exception {
        return Path.of(se.krka.kahlua.vm.KahluaThread.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static HashSet<String> helperCalls(byte[] transformed) {
        var calls = new HashSet<String>();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String descriptor, boolean isInterface) {
                        calls.add(name + ":" + owner.replace('/', '.') + ":" + method);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }
}
