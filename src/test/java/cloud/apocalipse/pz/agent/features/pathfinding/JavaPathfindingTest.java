package cloud.apocalipse.pz.agent.features.pathfinding;

import cloud.apocalipse.pz.agent.ApocBrAgent;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPathfindingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"true", "false"})
    void installedAgentControlsTheOriginalBackend(String setting) throws Exception {
        String smokeMain = JavaPathfindingOriginalMain.class.getName();
        Path isolated = directory.resolve("classes");
        Path smokeClass = isolated.resolve(smokeMain.replace('.', '/') + ".class");
        Files.createDirectories(smokeClass.getParent());
        Files.copy(Path.of("target/test-classes").resolve(isolated.relativize(smokeClass)), smokeClass);

        Path agent = directory.resolve("agent.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ApocBrAgent.class.getName());
        try (var jar = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
            jar.finish();
        }

        Path gameJar = Path.of(System.getProperty("pz.game.jar",
            "Z:/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid.jar")).toRealPath();
        Path testClasses = Path.of("target/test-classes").toRealPath();
        String dependencies = Arrays.stream(System.getProperty("surefire.test.class.path").split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(testClasses))
            .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(gameJar))
            .collect(Collectors.joining(File.pathSeparator));
        String classpath = String.join(File.pathSeparator, gameJar.toString(), isolated.toString(), dependencies);
        Path log = directory.resolve("original.log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-javaagent:" + agent, "-Dapocbr.lua.enabled=false"));
        if (setting != null) {
            command.add("-D" + AgentConfiguration.FORCE_JAVA_PATHFINDING_PROPERTY + "=" + setting);
        }
        command.addAll(List.of("-cp", classpath, smokeMain, gameJar.toString(),
            Boolean.toString(setting == null || Boolean.parseBoolean(setting))));
        var child = new ProcessBuilder(command)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), () -> "Pathfinding smoke timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), output);
            assertFalse(output.contains("[Byte Buddy] ERROR"), output);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
