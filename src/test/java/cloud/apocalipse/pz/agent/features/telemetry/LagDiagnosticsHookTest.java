package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.features.optimization.lua.LuaDispatchModule;
import cloud.apocalipse.pz.agent.features.workshop.AsyncWorkshopModule;
import cloud.apocalipse.pz.agent.features.workshop.WorkshopAsyncSmokeMain;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class LagDiagnosticsHookTest {
    @TempDir Path directory;

    @Test void registeredMatchersTransformOriginalJar() throws Exception {
        var builder = new AgentBuilder.Default().disableClassFormatChanges();
        for (var module : modules()) builder = module.configure(builder);
        var transformer = builder.makeRaw();
        List<HookCheck> checks = modules().stream().flatMap(module -> module.hookChecks().stream()).toList();

        try (var locator = ClassFileLocator.ForJarFile.of(originalJar().toFile())) {
            for (String name : checks.stream().map(HookCheck::targetClass).distinct().toList()) {
                byte[] original = locator.locate(name).resolve();
                byte[] transformed = transformer.transform(getClass().getClassLoader(),
                    name.replace('.', '/'), null, null, original);
                assertNotNull(transformed, name);
                var calls = new HashSet<String>();
                new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String sig,
                            String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String owner, String target, String desc,
                                    boolean itf) {
                                calls.add(method + ":" + owner.replace('/', '.') + ":" + target);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                checks.stream().filter(check -> check.targetClass().equals(name)).forEach(check ->
                    assertTrue(calls.contains(check.method() + ":" + check.helperClass() + ":" + check.helperMethod()),
                        check.toString()));
            }
        }
    }

    @Test void executesTransformedWorkshopEntryAndSteamPumpWithFixture() throws Exception {
        run(WorkshopAsyncSmokeMain.class, false, "Async Workshop hooks passed");
    }

    @Test void executesTransformedOriginalLuaEventAndGenericCall() throws Exception {
        run(TelemetryOriginalSmokeMain.class, true, "Original Lua callback hooks passed");
    }

    private void run(Class<?> fixture, boolean originalFirst, String expected) throws Exception {
        Path jar = directory.resolve("fixture-agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", getClass().getName());
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
        String classpath = System.getProperty("java.class.path");
        classpath = (originalFirst ? originalJar() : workshopFixtureClasses()) + File.pathSeparator + classpath;
        Path log = directory.resolve("fixture.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-javaagent:" + jar, "-cp", classpath, fixture.getName())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture did not exit");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(expected), output);
            assertFalse(output.contains("ERROR"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private Path workshopFixtureClasses() throws Exception {
        // Its Steam doubles share a package with these value types from the signed game JAR.
        // Copy just these entries unsigned into the disposable fixture classpath.
        Path fixture = directory.resolve("unsigned-workshop-values.jar");
        try (var source = new JarFile(originalJar().toFile());
             var output = new JarOutputStream(Files.newOutputStream(fixture))) {
            for (String name : List.of("ISteamWorkshopCallback", "SteamUGCDetails", "SteamWorkshopItem$ItemState")) {
                String entry = "zombie/core/znet/" + name + ".class";
                output.putNextEntry(new JarEntry(entry));
                try (var bytes = source.getInputStream(source.getJarEntry(entry))) {
                    bytes.transferTo(output);
                }
                output.closeEntry();
            }
        }
        return fixture;
    }

    private static Path originalJar() throws Exception {
        String configured = System.getProperty("pz.game.jar");
        if (configured != null) return Path.of(configured);
        return Path.of(se.krka.kahlua.vm.KahluaThread.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<cloud.apocalipse.pz.agent.AgentModule> modules() {
        return List.of(new AsyncWorkshopModule(), new TelemetryModule(), new LuaCallbackTelemetryModule(),
            new ServerPhaseTelemetryModule(), new LuaDispatchModule());
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        var builder = new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());
        for (var module : modules()) builder = module.configure(builder);
        builder.installOn(instrumentation);
    }
}
