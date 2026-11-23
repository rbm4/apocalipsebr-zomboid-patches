package cloud.apocalipse.pz.agent.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A fixture-owned cluster for the live streaming comparison. */
final class DisposableChunkPostgres implements AutoCloseable {
    private static final String ADMIN = "benchmark_migrator";
    static final String FRESH = "chunk_fresh";
    static final String SEEDED = "chunk_seeded";

    private final Path bin;
    private final Path output;
    private final Path data;
    private final int port;

    private DisposableChunkPostgres(Path bin, Path output, int port) {
        this.bin = bin;
        this.output = output;
        this.data = output.resolve("postgres-data");
        this.port = port;
    }

    static DisposableChunkPostgres start(Path output, Path bin) throws Exception {
        var cluster = new DisposableChunkPostgres(bin.toRealPath(), output, ServerTestContext.availablePort());
        try {
            cluster.run("initdb", "postgres-init.log", "-D", cluster.data.toString(), "-U", ADMIN,
                "--locale=C", "--encoding=UTF8", "--auth=trust");
            cluster.run("pg_ctl", "postgres-start.log", "-D", cluster.data.toString(),
                "-l", output.resolve("postgres.log").toString(), "-o",
                "-h 127.0.0.1 -p " + cluster.port + " -c shared_buffers=256MB -c unix_socket_directories=",
                "-t", "30", "-w", "start");
            try (var connection = cluster.connect("postgres"); var ddl = connection.createStatement()) {
                ddl.execute("CREATE ROLE chunk_migrator LOGIN");
                ddl.execute("CREATE ROLE chunk_engine LOGIN");
                ddl.execute("CREATE DATABASE " + FRESH + " OWNER chunk_migrator");
                ddl.execute("CREATE DATABASE " + SEEDED + " OWNER chunk_migrator");
            }
            for (String database : List.of(FRESH, SEEDED)) {
                try (var connection = cluster.connect(database); var ddl = connection.createStatement()) {
                    ddl.execute("CREATE EXTENSION pg_prewarm");
                }
            }
            return cluster;
        } catch (Exception | AssertionError failure) {
            try {
                cluster.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    int port() {
        return port;
    }

    long prewarm(String database) throws Exception {
        try (var connection = connect(database);
             var query = connection.createStatement();
             var rows = query.executeQuery("""
                 WITH relations AS (
                     SELECT oid FROM pg_class WHERE oid = 'apocbr_world.map_chunks'::regclass
                     UNION SELECT reltoastrelid FROM pg_class
                         WHERE oid = 'apocbr_world.map_chunks'::regclass AND reltoastrelid <> 0
                 ), warmed AS (
                     SELECT oid FROM relations
                     UNION SELECT indexrelid FROM pg_index JOIN relations ON indrelid = oid
                 )
                 SELECT sum(pg_prewarm(oid::regclass, 'buffer')),
                     pg_size_bytes(current_setting('shared_buffers')) / current_setting('block_size')::bigint
                 FROM warmed
                 """)) {
            if (!rows.next() || rows.getLong(1) <= 0 || rows.getLong(1) > rows.getLong(2)) {
                throw new IllegalStateException("Chunk relations must fit in shared buffers for the prewarmed comparison");
            }
            return rows.getLong(1);
        }
    }

    private Connection connect(String database) throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/" + database
            + "?user=" + ADMIN + "&connectTimeout=3&socketTimeout=30");
    }

    private void run(String executable, String log, String... arguments) throws Exception {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        var command = new ArrayList<String>();
        command.add(bin.resolve(executable + suffix).toString());
        command.addAll(List.of(arguments));
        Path logfile = output.resolve(log);
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logfile.toFile()).start();
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException(executable + " failed; inspect " + logfile);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Override public void close() throws Exception {
        if (Files.isRegularFile(data.resolve("postmaster.pid"))) {
            run("pg_ctl", "postgres-stop.log", "-D", data.toString(), "-m", "fast", "-t", "30", "-w", "stop");
        }
    }
}
