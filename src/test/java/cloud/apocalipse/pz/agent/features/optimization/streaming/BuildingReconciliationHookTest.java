package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.ApocBrAgent;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingReconciliationHookTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"default", "false", "true"})
    void executesOriginalLocalReconciliation(String setting) throws Exception {
        String main = BuildingReconciliationOriginalMain.class.getName();
        String classFile = main.replace('.', '/') + ".class";
        Path fixture = directory.resolve("fixture");
        Path destination = fixture.resolve(classFile);
        Files.createDirectories(destination.getParent());
        try (var resource = getClass().getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(resource);
            Files.copy(resource, destination);
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ApocBrAgent.class.getName());
        Path agent = directory.resolve("agent.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(agent), manifest)) {}

        var gameClass = getClass().getClassLoader()
            .getResource("zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid.class");
        assertNotNull(gameClass);
        Path original = Path.of(assertInstanceOf(JarURLConnection.class, gameClass.openConnection())
            .getJarFileURL().toURI()).toRealPath();
        Path testClasses = Path.of("target/test-classes").toRealPath();
        String dependencies = Arrays.stream(System.getProperty("surefire.test.class.path").split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(testClasses))
            .collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-javaagent:" + agent, "-Dapocbr.postgres.enabled=false", "-Dapocbr.lua.enabled=false"));
        if (!setting.equals("default")) {
            command.add("-D" + AgentConfiguration.CLIENT_BUILDING_RECONCILIATION_OPTIMIZATION + "=" + setting);
        }
        command.addAll(List.of("-cp", fixture + File.pathSeparator + dependencies,
            main, original.toString(), setting));
        Path log = directory.resolve("run.log");
        var child = new ProcessBuilder(command).directory(directory.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), () -> "Original-JAR fixture timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("ORIGINAL_BUILDING_RECONCILIATION_PASS"), output);
            assertFalse(output.contains("[Byte Buddy] ERROR"), output);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
