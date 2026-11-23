package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.ApocBrAgent;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.core.properties.IsoPropertyType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetLogTest {
    @Test
    void originalGameRunsAllSixHooksThroughPremain(@TempDir Path directory) throws Exception {
        Path game = classLocation(IsoPropertyType.class);
        Path testClasses = classLocation(getClass());
        String probe = AssetLogOriginalMain.class.getName();
        String resource = probe.replace('.', '/') + ".class";
        Path isolatedProbe = directory.resolve(resource);
        Files.createDirectories(isolatedProbe.getParent());
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            Files.copy(input, isolatedProbe);
        }

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ApocBrAgent.class.getName());
        Path agent = directory.resolve("premain.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
            output.finish();
        }

        String testClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String dependencies = Arrays.stream(testClasspath.split(Pattern.quote(File.pathSeparator)))
            .map(Path::of).map(path -> path.toAbsolutePath().normalize())
            .filter(path -> !path.equals(testClasses) && !path.equals(game))
            .map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        String classpath = String.join(File.pathSeparator, game.toString(), directory.toString(), dependencies);

        for (boolean enabled : new boolean[]{true, false}) {
            Path output = directory.resolve("asset-logs-" + enabled + ".log");
            var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-javaagent:" + agent, "-Dapocbr.postgres.enabled=false",
                "-D" + AgentConfiguration.ASSET_LOGS_HOTFIX_PROPERTY + "=" + enabled,
                "-cp", classpath, probe, game.toString(), Boolean.toString(enabled))
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Original-game asset diagnostic probe timed out");
                assertEquals(0, child.exitValue(), Files.readString(output));
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        }
    }

    private static Path classLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
    }
}
