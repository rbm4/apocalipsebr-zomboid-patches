package cloud.apocalipse.pz.agent.features.clientmenu;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.krka.kahlua.vm.KahluaThread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamLoginPacketPayloadTest {
    @Test void originalLoginPacketParsesThePayload(@TempDir Path directory) throws Exception {
        Path gameJar = classLocation(KahluaThread.class);
        Path agentClasses = classLocation(SteamLoginPacketPayload.class);
        String main = SteamLoginPacketPayloadOriginalMain.class.getName();
        String resource = main.replace('.', '/') + ".class";
        Path fixture = directory.resolve(resource);
        Files.createDirectories(fixture.getParent());
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            Files.copy(input, fixture);
        }

        // Keep the signed game classes outside Surefire's JVM and exclude all test doubles.
        String classpath = String.join(File.pathSeparator, gameJar.toString(), agentClasses.toString(), directory.toString());
        Path log = directory.resolve("packet-format.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-cp", classpath, main, gameJar.toString())
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), () -> "Original login packet check timed out: " + log);
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test void rejectsFieldsVanillaWouldTrimOrCannotRepresent() {
        assertThrows(IllegalArgumentException.class,
            () -> SteamLoginPacketPayload.serialize(" user", "fixture", "build"));
        assertThrows(IllegalArgumentException.class,
            () -> SteamLoginPacketPayload.serialize("user", "é".repeat(16_384), "build"));
    }

    private static Path classLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }
}
