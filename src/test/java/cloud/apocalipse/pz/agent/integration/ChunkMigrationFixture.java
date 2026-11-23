package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.experimental.chunks.ChunkObjectMutationsModule;
import cloud.apocalipse.pz.agent.integration.probe.ChunkStorageArm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the loopback roles created by Test-ChunkStorageReplay.ps1. */
public final class ChunkMigrationFixture {
    private static final String SEED = "pz.it.chunkMigrationSeed";
    static final String PORT = "pz.it.chunkMigrationPort";
    static final String BENCHMARK_SEED = "pz.it.chunkStorageBenchmarkSeed";
    static final String BENCHMARK_DATABASE = "pz.it.chunkStorageBenchmarkDatabase";
    private static final String DATASET = "integration-map";
    private static final String MAP = "cache/Saves/Multiplayer/agent-integration/map";

    private ChunkMigrationFixture() {
    }

    public static boolean enabled() {
        return System.getProperty(SEED) != null;
    }

    static void configure(Properties runner, Map<String, String> child, boolean streaming, boolean configured) {
        var arm = ChunkStorageArm.parse(runner.getProperty(ChunkStorageArm.PROPERTY));
        if (arm != null) {
            if (!streaming || configured || runner.getProperty(SEED) != null) {
                throw new IllegalArgumentException("Chunk storage benchmark requires an isolated streaming fixture");
            }
            child.put(ChunkStorageArm.PROPERTY, arm.name());
            child.put(AgentConfiguration.APOCBR_EVENTS_ENABLED_PROPERTY, "false");
            if (arm.postgres()) configurePostgres(runner, child);
            return;
        }
        if (runner.getProperty(SEED) == null) return;
        if (!streaming || configured || !"smoke".equals(runner.getProperty("pz.it.streamingProfile", "smoke"))) {
            throw new IllegalArgumentException("Chunk migration requires the isolated smoke streaming fixture");
        }
        configurePostgres(runner, child);
        child.put(AgentConfiguration.CHUNK_OBJECT_MUTATIONS_PROPERTY, "true");
    }

    private static void configurePostgres(Properties runner, Map<String, String> child) {
        child.put(AgentConfiguration.POSTGRES_ENABLED_PROPERTY, "true");
        child.put(AgentConfiguration.POSTGRES_ACCOUNTS_ENABLED_PROPERTY, "false");
        child.put(AgentConfiguration.DICTIONARY_ENABLED_PROPERTY, "false");
        child.put(AgentConfiguration.PLAYER_ENABLED_PROPERTY, "false");
        child.put(AgentConfiguration.POSTGRES_CHUNKS_ENABLED_PROPERTY, "true");
        child.put(AgentConfiguration.POSTGRES_CHUNK_DATASET_PROPERTY, DATASET);
        child.put(AgentConfiguration.POSTGRES_URL_PROPERTY, url(runner, "chunk_engine"));
        child.put(AgentConfiguration.POSTGRES_MIGRATION_URL_PROPERTY, url(runner, "chunk_migrator"));
    }

    static void seed(Path run, Properties runner) throws Exception {
        var arm = ChunkStorageArm.parse(runner.getProperty(ChunkStorageArm.PROPERTY));
        if (arm != null && arm.seeded()) {
            Path baseline = Path.of(runner.getProperty(BENCHMARK_SEED)).toRealPath();
            assertTrue(Files.isRegularFile(baseline.resolve("streaming-complete")), "Complete disk baseline required");
            Path source = map(baseline);
            copyFiles(source, map(run), chunkFiles(source));
            return;
        }
        if (runner.getProperty(SEED) == null) return;
        Path baseline = Path.of(runner.getProperty(SEED)).toRealPath();
        assertTrue(Files.isRegularFile(baseline.resolve("streaming-complete")), "Complete disk baseline required");
        Properties runtime = new Properties();
        try (var input = Files.newInputStream(baseline.resolve("runtime.properties"))) {
            runtime.load(input);
        }
        assertEquals("muldraugh-165-155-2x2-v2", runtime.getProperty("streaming.fixture"));
        Path source = baseline.resolve(MAP);
        Path target = run.resolve(MAP);
        List<Path> files = chunkFiles(source);
        assertEquals(256, files.size(), "Expected the four-cell disk fixture");
        // Keep one coordinate absent so the same run tests native generation after a DB and disk miss.
        copyFiles(source, target, files.subList(1, files.size()));
    }

    private static void copyFiles(Path source, Path target, List<Path> files) throws Exception {
        for (Path chunk : files) {
            Path destination = target.resolve(chunk);
            Files.createDirectories(destination.getParent());
            Files.copy(source.resolve(chunk), destination);
        }
    }

    public static void verify(Path run) throws Exception {
        Path source = Path.of(System.getProperty(SEED)).resolve(MAP);
        Path target = run.resolve(MAP);
        List<Path> files = chunkFiles(source);
        assertEquals(files.subList(1, files.size()), chunkFiles(target), "Save worker must write only to PostgreSQL");
        for (Path chunk : files.subList(1, files.size())) {
            assertEquals(-1L, Files.mismatch(source.resolve(chunk), target.resolve(chunk)), "Legacy file changed: " + chunk);
        }
        verifyRows(System.getProperties(), files);
    }

    public static void verifyObjectMutations(ServerTestContext server) throws Exception {
        List<String> expected = Files.readAllLines(server.directory().resolve("chunk-object-mutation-expected.txt"));
        byte[] payload = Base64.getDecoder().decode(expected.get(2));
        try (var connection = DriverManager.getConnection(url(System.getProperties(), "chunk_engine"));
             var query = connection.prepareStatement("""
                 SELECT revision, special_object, payload FROM apocbr_world.chunk_object_mutations
                 WHERE dataset = ? AND x = ? AND y = ? AND z = ? AND object_index = ?
                 """)) {
            for (int index = 0; index < 2; index++) {
                query.setString(1, DATASET);
                String[] key = expected.get(index).split(",");
                for (int field = 0; field < key.length; field++) {
                    query.setInt(field + 2, Integer.parseInt(key[field]));
                }
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next(), "Missing observed object row at " + expected.get(index));
                    assertTrue(rows.getLong(1) > 0);
                    assertEquals(index == 1, rows.getBoolean(2));
                    if (index == 0) assertNull(rows.getBytes(3), "Removed object must leave a tombstone");
                    else assertArrayEquals(payload, rows.getBytes(3), "Latest native object payload");
                    assertFalse(rows.next(), "Expected mutations to update the same live object row");
                }
            }
        }
        for (var hook : new ChunkObjectMutationsModule().hookChecks()) {
            server.assertHook(hook.targetClass(), hook.method(), hook.helperClass(), hook.helperMethod());
        }
    }

    static void verifyRows(Properties settings, List<Path> files) throws Exception {
        var expected = new HashSet<>(files);
        try (var connection = DriverManager.getConnection(url(settings, "chunk_engine"));
             var query = connection.prepareStatement("""
                 SELECT wx, wy, octet_length(payload) FROM apocbr_world.map_chunks WHERE dataset = ?
                 """)) {
            query.setString(1, DATASET);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    Path chunk = Path.of(Integer.toString(rows.getInt(1)), rows.getInt(2) + ".bin");
                    assertTrue(expected.remove(chunk), "Unexpected row for " + chunk);
                    assertTrue(rows.getInt(3) >= 17, "Native chunk header is missing");
                }
            }
            assertTrue(expected.isEmpty(), "Missing PostgreSQL chunks: " + expected);
        }
        try (var migration = DriverManager.getConnection(url(settings, "chunk_migrator"));
             var query = migration.createStatement();
             var rows = query.executeQuery("SELECT version, script, length(checksum) FROM apocbr_agent.schema_history ORDER BY version")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("V1__map_chunks.sql", rows.getString(2));
            assertEquals(64, rows.getInt(3));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            assertEquals("V2__chunk_object_mutations.sql", rows.getString(2));
            assertEquals(64, rows.getInt(3));
            assertFalse(rows.next());
        }
    }

    static Path map(Path run) { return run.resolve(MAP); }

    static List<Path> chunkFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.walk(directory, 2)) {
            return files.filter(Files::isRegularFile).map(directory::relativize)
                .filter(path -> path.getNameCount() == 2 && path.getName(0).toString().matches("-?\\d+")
                    && path.getFileName().toString().matches("-?\\d+\\.bin")).sorted().toList();
        }
    }

    static String url(Properties runner, String role) {
        int port = Integer.parseInt(runner.getProperty(PORT, "0"));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Expected a disposable PostgreSQL port");
        String database = runner.getProperty(BENCHMARK_DATABASE, "chunk_migration");
        if (!database.matches("chunk_[a-z_]+")) throw new IllegalArgumentException("Invalid fixture database");
        return "jdbc:postgresql://127.0.0.1:" + port + "/" + database + "?user=" + role;
    }
}
