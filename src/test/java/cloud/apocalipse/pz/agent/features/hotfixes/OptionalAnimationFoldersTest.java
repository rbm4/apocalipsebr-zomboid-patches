package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.random.RandStandard;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalAnimationFoldersTest {
    @Test
    void installedMatcherPreservesScanAndSilencesAbsentRoots(@TempDir Path directory) throws Exception {
        Path game = Path.of(AdvancedAnimator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String testClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = directory.resolve("probe.log");
        // Put the game JAR first in the child so other tests' game doubles cannot shadow it.
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-cp", game + File.pathSeparator + testClasspath,
            getClass().getName(), game.toString(), directory.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Animation folder probe timed out");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path originalJar = Path.of(args[0]).toRealPath();
        assertEquals(originalJar,
            Path.of(AdvancedAnimator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        Path mod = Path.of(args[1]);
        var hotfix = AgentModules.all().stream()
            .filter(OptionalAnimationFoldersModule.class::isInstance)
            .findFirst().orElseThrow();
        String property = AgentConfiguration.OPTIONAL_ANIMATION_FOLDERS_HOTFIX_PROPERTY;
        String target = OptionalAnimationFoldersModule.TARGET;
        String visitor = target + "$1";

        RandStandard.INSTANCE.init();
        var log = DebugLog.getInstance();
        var output = new ByteArrayOutputStream();
        DebugType.General.setLogSeverity(LogSeverity.General);
        log.setStdOut(output);
        log.setStdErr(output);
        try (var locator = ClassFileLocator.ForJarFile.of(originalJar.toFile())) {
            byte[] original = locator.locate(target).resolve();
            var builder = new AgentBuilder.Default().disableClassFormatChanges();
            ClassLoader parent = OptionalAnimationFoldersTest.class.getClassLoader();
            String internalName = target.replace('.', '/');

            Path media = Files.createDirectory(mod.resolve("media"));
            var vanilla = AdvancedAnimator.class.getDeclaredMethod("loadModMedia", String.class);
            vanilla.setAccessible(true);
            System.setProperty(property, "false");
            assertNotNull(hotfix.disabledReason());
            assertNull(hotfix.configure(builder).makeRaw().transform(parent, internalName, null, null, original));
            assertEquals(List.of(), vanilla.invoke(null, mod.toString()));
            String vanillaLog = output.toString();
            assertTrue(vanillaLog.contains("NoSuchFileException"), vanillaLog);
            assertTrue(vanillaLog.contains("AnimSets") && vanillaLog.contains("actiongroups"), vanillaLog);

            System.clearProperty(property);
            assertNull(hotfix.disabledReason());
            byte[] transformed = hotfix.configure(builder).makeRaw()
                .transform(parent, internalName, null, null, original);
            assertNotNull(transformed);
            var loader = new ByteArrayClassLoader.ChildFirst(parent, Map.of(
                target, transformed, visitor, locator.locate(visitor).resolve()));
            Class<?> animator = loader.loadClass(target);
            var scanMod = animator.getDeclaredMethod("loadModMedia", String.class);
            scanMod.setAccessible(true);
            output.reset();
            assertEquals(List.of(), scanMod.invoke(null, mod.toString()));

            Path animSets = Files.createDirectory(media.resolve("AnimSets"));
            Files.writeString(animSets.resolve("Example.XML"), "<anim/>");
            Files.writeString(animSets.resolve("ignored.txt"), "ignored");
            assertEquals(List.of("media/animsets/example.xml"), scanMod.invoke(null, mod.toString()));

            Path actionGroups = Files.createDirectory(media.resolve("actiongroups"));
            Files.writeString(actionGroups.resolve("Group.xml"), "<group/>");
            assertEquals(List.of("media/animsets/example.xml", "media/actiongroups/group.xml"),
                scanMod.invoke(null, mod.toString()));
            assertEquals("", output.toString());

            var searchFolders = animator.getMethod("searchFolders", URI.class, Path.class);
            var failure = assertThrows(InvocationTargetException.class,
                () -> searchFolders.invoke(null, null, animSets));
            assertInstanceOf(NullPointerException.class, failure.getCause());
        } finally {
            log.setStdOut(System.out);
            log.setStdErr(System.err);
            System.clearProperty(property);
        }
    }
}
