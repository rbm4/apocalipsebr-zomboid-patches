package cloud.apocalipse.pz.agent.features.lua.entities;

import static cloud.apocalipse.pz.agent.features.lua.entities.ApocBRModEntities.*;
import static cloud.apocalipse.pz.agent.features.lua.entities.EntityWorker.status;

import cloud.apocalipse.pz.agent.features.postgres.player.PlayerMutations;

import java.sql.*;
import java.util.*;

/** Prepared statements and detached results for the existing mod_data schema. */
final class EntityDatabase {
    private EntityDatabase() {}

    static Map<String, Object> character(
            Connection connection,
            Model model,
            String action,
            PlayerMutations.EntityContext context,
            List<Row> input)
            throws SQLException {
        if (!PlayerMutations.entityContextCurrent(context)) return status("STALE_SESSION");
        if (!activeCharacter(connection, context)) return status("IDENTITY_PENDING");
        if (!PlayerMutations.entityContextCurrent(context)) return status("STALE_SESSION");

        validate(connection, model);
        boolean patch = action.equals("patch");
        List<Object> existing =
                patch ? List.of() : characterRows(connection, model, context.character());
        boolean imported = action.equals("loadOrCreate") && existing.isEmpty();
        boolean writes = patch || action.equals("saveAll") || imported;
        if (writes) {
            for (Row row : input) {
                if (writeRow(connection, model, "save", row.values(), row.values(), row.revision())
                        != 1) throw new SQLException("Entity revision conflict", "40001");
            }
        }
        List<?> rows = existing;
        if (patch) {
            rows =
                    input.stream()
                            .map(
                                    row ->
                                            Map.of(
                                                    "values",
                                                    row.values(),
                                                    "revision",
                                                    Long.toString(row.revision() + 1)))
                            .toList();
        } else if (writes) {
            rows = characterRows(connection, model, context.character());
        }
        return Map.of("status", "OK", "imported", imported, "rows", rows);
    }

    static Map<String, Object> row(
            Connection connection,
            Model model,
            String action,
            Map<String, Object> keys,
            Map<String, Object> values,
            long expected)
            throws SQLException {
        validate(connection, model);
        return switch (action) {
            case "validate" -> status("OK");
            case "listForServer" ->
                    Map.of("status", "OK", "rows", serverRows(connection, model, keys));
            case "find" -> find(connection, model, keys);
            default -> mutateRow(connection, model, action, keys, values, expected);
        };
    }

    private static Map<String, Object> find(
            Connection connection, Model model, Map<String, Object> keys) throws SQLException {
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT "
                                + model.columns()
                                + ",revision FROM "
                                + model.target()
                                + " WHERE "
                                + model.where())) {
            bindKeys(statement, 1, model, keys);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return status("NOT_FOUND");
                long revision = rows.getLong("revision");
                if (revision <= 0) throw new SQLException("Invalid revision", "22003");
                return Map.of(
                        "status",
                        "OK",
                        "values",
                        read(rows, model),
                        "revision",
                        Long.toString(revision));
            }
        }
    }

    private static Map<String, Object> mutateRow(
            Connection connection,
            Model model,
            String action,
            Map<String, Object> keys,
            Map<String, Object> values,
            long expected)
            throws SQLException {
        if (writeRow(connection, model, action, keys, values, expected) == 1) {
            return action.equals("delete")
                    ? status("OK")
                    : Map.of("status", "OK", "revision", Long.toString(expected + 1));
        }
        if (action.equals("append")) {
            // Reconcile a retry after an ambiguous commit without changing consumer-owned fields.
            Object existing = find(connection, model, keys).get("values");
            if (existing instanceof Map<?, ?> found && sameValues(values, found))
                return Map.of("status", "OK", "duplicate", true);
        }
        return status("CONFLICT");
    }

    private static int writeRow(
            Connection connection,
            Model model,
            String action,
            Map<String, Object> keys,
            Map<String, Object> values,
            long expected)
            throws SQLException {
        boolean delete = action.equals("delete");
        boolean insert = !delete && expected == 0;
        List<Field> fields =
                insert
                        ? model.fields()
                        : model.fields().stream()
                                .filter(field -> !field.key() && values.containsKey(field.name()))
                                .toList();
        String sql;
        if (delete) {
            sql = "DELETE FROM " + model.target() + " WHERE " + model.where() + " AND revision=?";
        } else if (insert) {
            sql =
                    "INSERT INTO "
                            + model.target()
                            + " ("
                            + model.columns()
                            + ",revision) VALUES ("
                            + String.join(",", Collections.nCopies(fields.size(), "?"))
                            + ",1) ON CONFLICT DO NOTHING";
        } else {
            String assignments =
                    String.join(
                            ",", fields.stream().map(field -> quote(field.name()) + "=?").toList());
            sql =
                    "UPDATE "
                            + model.target()
                            + " SET "
                            + (assignments.isEmpty() ? "" : assignments + ",")
                            + "revision=revision+1 WHERE "
                            + model.where()
                            + " AND revision=?";
        }
        try (PreparedStatement statement = prepare(connection, sql)) {
            int index = 1;
            for (Field field : fields) statement.setObject(index++, values.get(field.name()));
            if (!insert) {
                index = bindKeys(statement, index, model, keys);
                statement.setLong(index, expected);
            }
            return statement.executeUpdate();
        }
    }

    static Map<String, Object> document(
            Connection connection,
            long server,
            String world,
            String document,
            String revision,
            String mutation,
            String payload)
            throws SQLException {
        if (payload == null) return loadDocument(connection, server, world, document);
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT status,new_revision FROM"
                            + " mod_data.mutate_entity_document(?,?,?,?,?,?::jsonb)")) {
            statement.setLong(1, server);
            statement.setString(2, world);
            statement.setString(3, document);
            statement.setLong(4, Long.parseLong(revision));
            statement.setObject(5, UUID.fromString(mutation));
            statement.setString(6, payload);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing mutation result", "22000");
                return Map.of(
                        "status", rows.getString(1), "revision", Long.toString(rows.getLong(2)));
            }
        }
    }

    private static Map<String, Object> loadDocument(
            Connection connection, long server, String world, String document) throws SQLException {
        // One statement observes the state revision and its records from the same snapshot.
        try (PreparedStatement statement =
                prepare(
                        connection,
                        """
                        SELECT h.revision,r.collection,r.record_id,r.payload::text
                        FROM mod_data.entity_document_state h
                        LEFT JOIN mod_data.entity_document_record r USING(server_id,world,document_id)
                        WHERE h.server_id=? AND h.world=? AND h.document_id=?
                        ORDER BY r.collection,r.record_id LIMIT 100002
                        """)) {
            statement.setLong(1, server);
            statement.setString(2, world);
            statement.setString(3, document);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return status("NOT_FOUND");
                String revision = Long.toString(rows.getLong(1));
                List<Object> records = new ArrayList<>();
                long characters = 0;
                do {
                    String collection = rows.getString(2);
                    if (collection == null) continue;
                    String key = rows.getString(3);
                    String payload = rows.getString(4);
                    characters += (long) collection.length() + key.length() + payload.length();
                    if (records.size() >= 100_000 || characters > 33_554_432)
                        throw new SQLException("Document limit", "54000");
                    records.add(Map.of("collection", collection, "key", key, "payload", payload));
                } while (rows.next());
                return Map.of(
                        "status", "OK", "revision", revision, "records", List.copyOf(records));
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql)
            throws SQLException {
        EntityWorker.checkInterrupted();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(2);
            return statement;
        } catch (SQLException failure) {
            try {
                statement.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private static List<Object> characterRows(Connection connection, Model model, UUID character)
            throws SQLException {
        List<Object> result = new ArrayList<>();
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT "
                                + model.columns()
                                + ",revision FROM "
                                + model.target()
                                + " WHERE character_id=? ORDER BY "
                                + String.join(
                                        ",",
                                        model.keys().stream().map(f -> quote(f.name())).toList())
                                + " LIMIT 257")) {
            statement.setObject(1, character);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= 256)
                        throw new SQLException("Character row limit", "22003");
                    long revision = rows.getLong("revision");
                    if (revision <= 0 || revision == Long.MAX_VALUE)
                        throw new SQLException("Invalid revision", "22003");
                    result.add(
                            Map.of(
                                    "values",
                                    read(rows, model),
                                    "revision",
                                    Long.toString(revision)));
                }
            }
        }
        return result;
    }

    private static boolean activeCharacter(
            Connection connection, PlayerMutations.EntityContext context) throws SQLException {
        var c = context;
        // Hold the slot lock through commit, serializing initialization and character replacement.
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT p.active_character_id FROM player_rel.player_slot p JOIN"
                            + " player_rel.character c ON c.id=p.active_character_id WHERE"
                            + " p.world=? AND p.identity_mode=? AND p.identity_key=? AND"
                            + " p.player_index=? FOR UPDATE OF p")) {
            statement.setObject(1, c.world());
            statement.setObject(2, c.identityMode());
            statement.setObject(3, c.identity());
            statement.setObject(4, c.slot());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && context.character().equals(rows.getObject(1, UUID.class));
            }
        }
    }

    private static List<Object> serverRows(
            Connection connection, Model model, Map<String, Object> scope) throws SQLException {
        List<Object> result = new ArrayList<>();
        String order = String.join(",", model.keys().stream().map(f -> quote(f.name())).toList());
        try (PreparedStatement statement =
                prepare(
                        connection,
                        "SELECT "
                                + model.columns()
                                + ",revision FROM "
                                + model.target()
                                + " WHERE server_id=? AND world=? ORDER BY "
                                + order
                                + " LIMIT 257")) {
            statement.setObject(1, scope.get("server_id"));
            statement.setObject(2, scope.get("world"));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= 256) throw new SQLException("Server row limit", "54000");
                    long revision = rows.getLong("revision");
                    if (revision <= 0 || revision == Long.MAX_VALUE)
                        throw new SQLException("Invalid revision", "22003");
                    result.add(
                            Map.of(
                                    "values",
                                    read(rows, model),
                                    "revision",
                                    Long.toString(revision)));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameValues(Map<String, Object> expected, Map<?, ?> actual) {
        return expected.size() == actual.size()
                && expected.entrySet().stream()
                        .allMatch(
                                entry ->
                                        Objects.equals(
                                                entry.getValue() instanceof UUID uuid
                                                        ? uuid.toString()
                                                        : entry.getValue(),
                                                actual.get(entry.getKey())));
    }

    private static int bindKeys(
            PreparedStatement statement, int index, Model model, Map<String, Object> keys)
            throws SQLException {
        for (Field field : model.keys()) statement.setObject(index++, keys.get(field.name()));
        return index;
    }

    private static void validate(Connection connection, Model model) throws SQLException {
        // No automatic DDL. Validate names and JDBC types on the database worker.
        try (PreparedStatement statement =
                        prepare(
                                connection,
                                "SELECT "
                                        + model.columns()
                                        + ",revision FROM "
                                        + model.target()
                                        + " WHERE false");
                ResultSet rows = statement.executeQuery()) {
            ResultSetMetaData metadata = rows.getMetaData();
            int index = 1;
            for (Field field : model.fields()) {
                int actual = metadata.getColumnType(index++);
                boolean valid =
                        switch (field.type()) {
                            case "uuid" -> metadata.getColumnTypeName(index - 1).equals("uuid");
                            case "string", "text" ->
                                    actual == Types.VARCHAR || actual == Types.LONGVARCHAR;
                            case "number" -> actual == Types.DOUBLE;
                            case "integer" -> actual == Types.BIGINT;
                            case "boolean" -> actual == Types.BOOLEAN || actual == Types.BIT;
                            default -> false;
                        };
                if (!valid) throw new SQLException("Entity schema mismatch", "42804");
            }
            if (metadata.getColumnType(index) != Types.BIGINT)
                throw new SQLException("Revision schema mismatch", "42804");
        }
        Set<String> primary = new HashSet<>();
        try (ResultSet keys =
                connection.getMetaData().getPrimaryKeys(null, "mod_data", model.table())) {
            while (keys.next()) primary.add(keys.getString("COLUMN_NAME"));
        }
        if (!primary.equals(new HashSet<>(model.keys().stream().map(Field::name).toList())))
            throw new SQLException("Entity primary key mismatch", "42804");
    }

    private static Map<String, Object> read(ResultSet rows, Model model) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : model.fields()) {
            Object value = rows.getObject(field.name());
            if (value == null) throw new SQLException("Null entity field", "22004");
            if (value instanceof UUID) value = value.toString();
            // Reuse input validation, including finite values and Lua-exact integer bounds.
            Object checked = scalar(field, value);
            values.put(field.name(), checked instanceof UUID ? checked.toString() : checked);
        }
        return Collections.unmodifiableMap(values);
    }
}
