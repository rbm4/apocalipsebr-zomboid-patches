package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.ApocBrAgent;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ChunkAsyncSendTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void runsOriginalDownloadPipeline(boolean enabled) throws Exception {
        String className = ChunkAsyncSendOriginalMain.class.getName();
        Path testClasses = Path.of("target/test-classes").toRealPath();
        Path fixture = directory.resolve("fixture");
        Path fixturePackage = fixture.resolve(className.substring(0, className.lastIndexOf('.')).replace('.', '/'));
        Files.createDirectories(fixturePackage);
        try (var nested = Files.newDirectoryStream(testClasses.resolve(fixture.relativize(fixturePackage)),
                "ChunkAsyncSendOriginalMain*.class")) {
            for (Path entry : nested) Files.copy(entry, fixturePackage.resolve(entry.getFileName()));
        }

        Path agent = directory.resolve("agent.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ApocBrAgent.class.getName());
        try (var output = new JarOutputStream(Files.newOutputStream(agent), manifest)) {}

        // Locate the class as a resource without mixing signed game classes and
        // unsigned game doubles in the parent test JVM.
        var resource = getClass().getClassLoader().getResource("zombie/network/PlayerDownloadServer.class");
        assertNotNull(resource);
        var gameArchive = assertInstanceOf(JarURLConnection.class, resource.openConnection());
        Path game = Path.of(gameArchive.getJarFileURL().toURI()).toRealPath();
        String dependencies = Arrays.stream(System.getProperty("surefire.test.class.path").split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(testClasses))
            .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(game))
            .collect(Collectors.joining(File.pathSeparator));
        String classpath = String.join(File.pathSeparator, game.toString(), fixture.toString(), dependencies);
        Path log = directory.resolve("run.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-javaagent:" + agent, "-Dapocbr.lua.enabled=false", "-Dapocbr.postgres.enabled=false",
            "-D" + AgentConfiguration.CHUNK_ASYNC_SEND_OPTIMIZATION + "=" + enabled,
            "-cp", classpath, className, game.toString(), Boolean.toString(enabled))
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), () -> "Original-JAR fixture timed out: " + log);
            String output = Files.readString(log);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("ORIGINAL_CHUNK_ASYNC_SEND_PASS"), output);
            assertFalse(output.contains("[Byte Buddy] ERROR"), output);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

}
