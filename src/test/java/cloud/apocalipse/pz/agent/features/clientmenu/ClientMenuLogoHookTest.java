package cloud.apocalipse.pz.agent.features.clientmenu;

import cloud.apocalipse.pz.agent.HookCheck;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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

class ClientMenuLogoHookTest {
    @TempDir Path directory;

    @Test void registeredLogoHookTransformsOriginalSignature() throws Exception {
        assertStartupHook("startup-logo-skip");
    }

    @Test void registeredWarningHookTransformsOriginalSignature() throws Exception {
        assertStartupHook("startup-warning-skip");
    }

    private void assertStartupHook(String id) throws Exception {
        ClientMenuModule module = new ClientMenuModule();
        module.initialize();
        var transformer = module.configure(new AgentBuilder.Default().disableClassFormatChanges()).makeRaw();
        HookCheck check = module.hookChecks().stream().filter(value -> value.id().equals(id))
            .findFirst().orElseThrow();
        try (var locator = ClassFileLocator.ForJarFile.of(originalJar().toFile())) {
            byte[] transformed = transformer.transform(getClass().getClassLoader(),
                check.targetClass().replace('.', '/'), null, null, locator.locate(check.targetClass()).resolve());
            assertNotNull(transformed);
            var calls = new java.util.HashSet<String>();
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
            assertTrue(calls.contains(check.method() + ":" + check.helperClass() + ":" + check.helperMethod()));
        }
    }

    @Test void transformedOriginalClientStartupSkipsLogosAndHonorsWarningSetting() throws Exception {
        Path agent = directory.resolve("logo-fixture-agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", LogoSkipOriginalSmokeMain.class.getName());
        try (var output = new JarOutputStream(Files.newOutputStream(agent), manifest)) { }
        Path log = directory.resolve("logo-fixture.log");
        String classpath = originalJar() + File.pathSeparator + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-javaagent:" + agent, "-cp", classpath, LogoSkipOriginalSmokeMain.class.getName())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "logo fixture did not exit");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Client-menu startup logo hook passed"), output);
            assertTrue(output.contains("Client-menu startup warning hook passed"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path originalJar() throws Exception {
        String configured = System.getProperty("pz.game.jar");
        if (configured != null) return Path.of(configured);
        return Path.of(se.krka.kahlua.vm.KahluaThread.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
