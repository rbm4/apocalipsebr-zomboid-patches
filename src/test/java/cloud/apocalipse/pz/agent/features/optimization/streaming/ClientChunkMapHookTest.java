package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.ApocBrAgent;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zombie.core.Core;
import zombie.debug.DebugOptions;
import zombie.iso.IsoChunkMap;
import zombie.network.GameServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChunkMapHookTest {
    @TempDir Path directory;

    @Test void productionAgentSizesTheOriginalChunkMapBeforeWorldAllocation() throws Exception {
        String main = ClientChunkMapSmokeMain.class.getName();
        String resource = main.replace('.', '/') + ".class";
        Path probe = directory.resolve("probe");
        Path destination = probe.resolve(resource);
        Files.createDirectories(destination.getParent());
        try (var bytes = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(bytes);
            Files.copy(bytes, destination);
        }
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Premain-Class", ApocBrAgent.class.getName());
        Path agent = directory.resolve("premain.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(agent), manifest)) {
            jar.finish();
        }

        Path testClasses = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        Path original = Path.of(IsoChunkMap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var entries = new ArrayList<String>();
        entries.add(probe.toString());
        String testClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : testClasspath.split(Pattern.quote(File.pathSeparator))) {
            if (!Path.of(entry).toAbsolutePath().normalize().equals(testClasses)) entries.add(entry);
        }
        for (String setting : new String[]{"default", "false", "true"}) {
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
            command.add("-Xverify:all");
            command.add("-javaagent:" + agent);
            if (!setting.equals("default")) {
                command.add("-D" + AgentConfiguration.CLIENT_CHUNK_MAP_OPTIMIZATION + "=" + setting);
            }
            command.add("-cp");
            command.add(String.join(File.pathSeparator, entries));
            command.add(main);
            command.add(original.toString());
            command.add(setting);
            Path output = directory.resolve(setting + ".log");
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Chunk map smoke timed out for " + setting);
                assertEquals(0, process.exitValue(), Files.readString(output));
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}

final class ClientChunkMapSmokeMain {
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]).toRealPath();
        for (Class<?> type : new Class<?>[]{IsoChunkMap.class, Core.class, GameServer.class}) {
            Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isSameFile(original, source)) throw new AssertionError(type.getName() + " came from " + source);
        }
        boolean enabled = Boolean.parseBoolean(args[1]);
        setResolution(1920, 1080);
        expectWidth(enabled ? 13 : 19);
        expectBufferSizes(enabled ? 13 : 19);

        GameServer.server = true;
        expectWidth(19);
        GameServer.server = false;
        setResolution(1024, 576);
        expectWidth(11);

        setResolution(3840, 2160);
        Core.class.getField("debug").setBoolean(null, true);
        DebugOptions.instance.worldChunkMap9x9.setValue(true);
        expectWidth(9);
        DebugOptions.instance.worldChunkMap9x9.setValue(false);
        expectWidth(enabled ? 13 : 19);
    }

    private static void setResolution(int width, int height) throws ReflectiveOperationException {
        // Test compilation also sees a Core double. The fork uses the original fields without opening a display.
        Core.class.getField("width").setInt(null, width);
        Core.class.getField("height").setInt(null, height);
    }

    private static void expectWidth(int width) {
        IsoChunkMap.CalcChunkWidth();
        if (IsoChunkMap.chunkGridWidth != width || IsoChunkMap.chunkWidthInTiles != width * 8) {
            throw new AssertionError("Expected " + width + " chunks, got " + IsoChunkMap.chunkGridWidth
                + " chunks and " + IsoChunkMap.chunkWidthInTiles + " tiles");
        }
    }

    private static void expectBufferSizes(int width) throws ReflectiveOperationException {
        var map = new IsoChunkMap(null);
        try {
            for (String name : new String[]{"chunksSwapA", "chunksSwapB"}) {
                var field = IsoChunkMap.class.getDeclaredField(name);
                field.setAccessible(true);
                int size = ((Object[]) field.get(map)).length;
                if (size != width * width) throw new AssertionError(name + " has " + size + " slots");
            }
        } finally {
            map.Dispose();
        }
    }
}
