package cloud.apocalipse.pz.agent.features.postgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;

/** Owns the version history for the agent-managed schema. Existing application schemas are independent. */
public final class PostgresMigrations {
    private static final List<String> MIGRATIONS = List.of("V1__map_chunks.sql", "V2__chunk_object_mutations.sql");

    private PostgresMigrations() {
    }

    public static void prepareChunkSchema(String migrationUrl) {
        try {
            String engineRole;
            String database;
            try (Connection engine = PostgresRuntime.borrowBackground();
                 var query = engine.createStatement();
                 var rows = query.executeQuery("SELECT current_user, current_database()")) {
                rows.next();
                engineRole = rows.getString(1);
                database = rows.getString(2);
            }
            if (migrationUrl != null && !migrationUrl.isBlank()) migrate(migrationUrl, engineRole, database);
            try (Connection engine = PostgresRuntime.borrowBackground();
                 var query = engine.createStatement();
                 var rows = query.executeQuery("""
                    SELECT has_table_privilege(current_user, 'apocbr_world.map_chunks', 'SELECT')
                       AND has_table_privilege(current_user, 'apocbr_world.map_chunks', 'INSERT')
                       AND has_table_privilege(current_user, 'apocbr_world.map_chunks', 'UPDATE')
                       AND has_schema_privilege(current_user, 'apocbr_world', 'USAGE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new SQLException("Missing chunk storage grants", "42501");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(PostgresErrors.sanitized("chunk schema preparation", failure).getMessage());
        }
    }

    public static void verifyChunkObjectSchema() {
        try (Connection engine = PostgresRuntime.borrowBackground();
             var query = engine.createStatement();
             var rows = query.executeQuery("""
                 SELECT has_table_privilege(current_user, 'apocbr_world.chunk_object_mutations', 'SELECT')
                    AND has_table_privilege(current_user, 'apocbr_world.chunk_object_mutations', 'INSERT')
                    AND has_table_privilege(current_user, 'apocbr_world.chunk_object_mutations', 'UPDATE')
                 """)) {
            if (!rows.next() || !rows.getBoolean(1)) throw new SQLException("Missing chunk object grants", "42501");
        } catch (SQLException failure) {
            throw new IllegalStateException(PostgresErrors.sanitized("chunk object schema verification", failure).getMessage());
        }
    }

    private static void migrate(String url, String engineRole, String database) throws SQLException {
        var source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setConnectTimeout(3);
        source.setSocketTimeout(15);
        try (Connection migration = source.getConnection()) {
            migration.setAutoCommit(false);
            try {
                try (var query = migration.createStatement();
                     var rows = query.executeQuery("SELECT current_user, current_database()")) {
                    rows.next();
                    if (engineRole.equals(rows.getString(1)) || !database.equals(rows.getString(2))) {
                        throw new IllegalStateException("Chunk migrations require a distinct owner of the engine database");
                    }
                }
                apply(migration, engineRole);
                migration.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    migration.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    private static void apply(Connection migration, String engineRole) throws SQLException {
        try (var ddl = migration.createStatement()) {
            ddl.execute("SET LOCAL lock_timeout = '5s'");
            ddl.execute("SELECT pg_advisory_xact_lock(1768723180, 1)");
            ddl.execute("CREATE SCHEMA IF NOT EXISTS apocbr_world");
            ddl.execute("CREATE SCHEMA IF NOT EXISTS apocbr_agent");
            ddl.execute("""
                CREATE TABLE IF NOT EXISTS apocbr_agent.schema_history (
                    version integer PRIMARY KEY,
                    script text NOT NULL,
                    checksum text NOT NULL,
                    installed_at timestamptz NOT NULL DEFAULT now()
                )
                """);
            try (var rows = ddl.executeQuery("SELECT COALESCE(max(version), 0) FROM apocbr_agent.schema_history")) {
                rows.next();
                if (rows.getInt(1) > MIGRATIONS.size()) {
                    throw new IllegalStateException("Agent-managed schema is newer than this agent");
                }
            }
            for (int index = 0; index < MIGRATIONS.size(); index++) {
                applyVersion(migration, index + 1, MIGRATIONS.get(index));
            }
            String role = "\"" + engineRole.replace("\"", "\"\"") + "\"";
            ddl.execute("GRANT USAGE, CREATE ON SCHEMA apocbr_world TO " + role);
            ddl.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA apocbr_world TO " + role);
            ddl.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA apocbr_world TO " + role);
            ddl.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA apocbr_world GRANT ALL ON TABLES TO " + role);
            ddl.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA apocbr_world GRANT ALL ON SEQUENCES TO " + role);
        }
    }

    private static void applyVersion(Connection migration, int version, String script) throws SQLException {
        String sql;
        try (var resource = PostgresMigrations.class.getResourceAsStream(
                "/cloud/apocalipse/pz/agent/postgres/migrations/" + script)) {
            if (resource == null) throw new IOException("Missing resource");
            sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read PostgreSQL migration " + script, failure);
        }
        String checksum;
        try {
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        try (var query = migration.prepareStatement(
                "SELECT script, checksum FROM apocbr_agent.schema_history WHERE version = ?")) {
            query.setInt(1, version);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!script.equals(rows.getString(1)) || !checksum.equals(rows.getString(2))) {
                        throw new IllegalStateException("PostgreSQL migration history mismatch for " + script);
                    }
                    return;
                }
            }
        }
        try (var ddl = migration.createStatement();
             var history = migration.prepareStatement(
                 "INSERT INTO apocbr_agent.schema_history(version, script, checksum) VALUES (?, ?, ?)")) {
            ddl.execute(sql);
            history.setInt(1, version);
            history.setString(2, script);
            history.setString(3, checksum);
            history.executeUpdate();
        }
    }
}
