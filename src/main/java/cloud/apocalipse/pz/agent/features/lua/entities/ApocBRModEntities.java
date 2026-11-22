package cloud.apocalipse.pz.agent.features.lua.entities;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.postgres.player.PlayerMutations;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;

import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;

import java.util.*;

/** Server Lua contract used by ApocBR/Entities. Lua values never reach the database worker. */
public final class ApocBRModEntities {
    private static ApocBRModEntities installed;
    private final Map<String, Model> models = new LinkedHashMap<>();
    private final Thread owner = Thread.currentThread();
    private final EntityWorker worker =
            new EntityWorker(AgentConfiguration.postgresSettings().enabled());
    private final KahluaTable environment = LuaManager.env;

    private ApocBRModEntities() {}

    public static synchronized void install() {
        if (!GameServer.server) return;
        if (installed != null) {
            installed.worker.checkIdle();
            installed.worker.close();
        }
        var bridge = new ApocBRModEntities();
        bridge.bindApi();
        installed = bridge;
    }

    public static synchronized void shutdown() {
        if (installed != null) installed.worker.close();
    }

    private void bindApi() {
        KahluaTable api = LuaManager.platform.newTable();
        bind(api, "define", 2, (f) -> define(text(f.get(0)), table(f.get(1))));
        bindRequest(
                api,
                "submit",
                5,
                f ->
                        submit(
                                text(f.get(0)),
                                text(f.get(1)),
                                table(f.get(2)),
                                table(f.get(3)),
                                text(f.get(4))));
        bind(api, "poll", 0, (f) -> poll());
        bindRequest(api, "listForServer", 1, f -> listForServer(text(f.get(0))));
        bind(api, "serverScope", 0, (f) -> lua(Map.of("server_id", serverId(), "world", world())));
        bind(api, "encode", 1, (f) -> encode(table(f.get(0))));
        bind(api, "decode", 1, (f) -> decode(text(f.get(0))));
        bindRequest(api, "documentLoad", 1, f -> documentRequest(text(f.get(0)), null, null, null));
        bindRequest(
                api,
                "documentMutate",
                4,
                f ->
                        documentRequest(
                                text(f.get(0)), text(f.get(1)), text(f.get(2)), table(f.get(3))));
        bind(api, "character", 1, (f) -> character(player(f.get(0))));
        bindRequest(
                api,
                "submitCharacter",
                5,
                f ->
                        submitCharacter(
                                text(f.get(0)),
                                text(f.get(1)),
                                player(f.get(2)),
                                text(f.get(3)),
                                table(f.get(4))));
        bind(api, "messageContext", 1, (f) -> messageContext(player(f.get(0))));
        bind(api, "newMessageId", 0, (f) -> UUID.randomUUID().toString());
        bindRequest(
                api,
                "append",
                4,
                f -> append(text(f.get(0)), f.get(1), text(f.get(2)), table(f.get(3))));
        environment.rawset("ApocBRModEntities", api);
    }

    private interface Call {
        Object run(se.krka.kahlua.vm.LuaCallFrame frame);
    }

    private void bind(KahluaTable api, String name, int argc, Call call) {
        api.rawset(
                name,
                (JavaFunction)
                        (frame, count) -> {
                            guard();
                            if (count != argc)
                                throw new IllegalArgumentException("Invalid entity argument count");
                            frame.push(call.run(frame));
                            return 1;
                        });
    }

    private void bindRequest(KahluaTable api, String name, int argc, Call call) {
        bind(
                api,
                name,
                argc,
                frame -> worker.available() ? call.run(frame) : admission(false, "UNAVAILABLE"));
    }

    record Field(String name, String type, boolean key) {}

    record Model(String name, String table, List<Field> fields, boolean appendOnly) {
        String target() {
            return "mod_data.\"" + table + "\"";
        }

        List<Field> keys() {
            return fields.stream().filter(Field::key).toList();
        }

        String columns() {
            return String.join(",", fields.stream().map(f -> quote(f.name())).toList());
        }

        String where() {
            return String.join(" AND ", keys().stream().map(f -> quote(f.name()) + "=?").toList());
        }
    }

    private void guard() {
        if (!GameServer.server || Thread.currentThread() != owner || LuaManager.env != environment)
            throw new IllegalStateException("Server Lua environment and owner thread required");
    }

    private static int serverId() {
        String value =
                AgentConfiguration.systemProperty(
                        AgentConfiguration.APOCBR_POSTGRES_SERVER_ID_PROPERTY);
        try {
            int id = Integer.parseInt(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(
                "Set apocbr.postgres.serverId to a registered positive player_rel.server.id");
    }

    private static String world() {
        return Objects.requireNonNull(IsoWorld.instance.getWorld(), "World required");
    }

    private KahluaTable admit(boolean mutates, EntityWorker.Operation operation) {
        return (KahluaTable) lua(worker.submit(mutates, operation));
    }

    private Object poll() {
        return lua(worker.poll());
    }

    private static KahluaTable admission(boolean accepted, String value) {
        return (KahluaTable) lua(Map.of("accepted", accepted, accepted ? "id" : "status", value));
    }

    private static String text(Object value) {
        if (!(value instanceof String s)) throw new IllegalArgumentException("String required");
        return s;
    }

    private static KahluaTable table(Object value) {
        if (!(value instanceof KahluaTable t)) throw new IllegalArgumentException("Table required");
        return t;
    }

    private static String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]{0,47}") || value.equals("revision"))
            throw new IllegalArgumentException("Invalid mapping identifier");
        return value;
    }

    static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static IsoPlayer player(Object value) {
        if (!(value instanceof IsoPlayer p))
            throw new IllegalArgumentException("Server player required");
        return p;
    }

    private static KahluaTable character(IsoPlayer player) {
        var context = PlayerMutations.entityContext(player);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", context == null ? "IDENTITY_PENDING" : "OK");
        if (context != null) {
            result.put("characterId", context.character().toString());
            result.put("session", context.session().toString());
            result.put("sourceServerId", serverId());
            result.put("steamId", Long.toUnsignedString(player.getSteamID()));
        }
        return (KahluaTable) lua(result);
    }

    record Row(Map<String, Object> values, long revision) {}

    // An opaque, detached capability created only from the authoritative live binding.
    // It deliberately survives disconnect/death; historical events do not target the new life.
    private record MessageContext(
            UUID character,
            UUID session,
            long server,
            String world,
            String mode,
            String identity,
            long slot) {}

    private static Object messageContext(IsoPlayer player) {
        var context = PlayerMutations.entityContext(player);
        if (context == null) return lua(Map.of("status", "IDENTITY_PENDING"));
        var token =
                new MessageContext(
                        context.character(),
                        context.session(),
                        serverId(),
                        context.world(),
                        context.identityMode(),
                        context.identity(),
                        context.slot());
        return lua(
                Map.of(
                        "status",
                        "OK",
                        "characterId",
                        token.character().toString(),
                        "session",
                        token.session().toString(),
                        "token",
                        token));
    }

    private Object append(String name, Object token, String messageId, KahluaTable input) {
        Model model = models.get(name);
        if (model == null || !model.appendOnly())
            throw new IllegalArgumentException("Append-only mapping required");
        if (!(token instanceof MessageContext context))
            throw new IllegalArgumentException("Trusted message context required");
        if (model.keys().size() != 1 || !model.keys().get(0).name().equals("message_id"))
            throw new IllegalArgumentException("Message UUID key required");
        KahluaTable values = LuaManager.platform.newTable();
        var iterator = input.iterator();
        while (iterator.advance()) {
            String key = text(iterator.getKey());
            if (!Set.of("event_type", "payload").contains(key))
                throw new IllegalArgumentException("Message field is server managed");
            values.rawset(key, iterator.getValue());
        }
        values.rawset("message_id", UUID.fromString(messageId).toString());
        values.rawset("character_id", context.character().toString());
        values.rawset("session_id", context.session().toString());
        values.rawset("source_server_id", context.server());
        values.rawset("world", context.world());
        values.rawset("identity_mode", context.mode());
        values.rawset("identity_key", context.identity());
        values.rawset("player_index", context.slot());
        Map<String, Object> captured = copy(model, values, false), keys = keys(model, captured);
        return admit(
                true,
                connection -> EntityDatabase.row(connection, model, "append", keys, captured, 0));
    }

    private Object submitCharacter(
            String name, String action, IsoPlayer player, String session, KahluaTable input) {
        Model model = models.get(name);
        if (model == null) throw new IllegalArgumentException("Unknown entity");
        if (model.appendOnly())
            throw new IllegalArgumentException("Append-only mapping cannot be mutated");
        if (!Set.of("loadOrCreate", "saveAll", "patch").contains(action))
            throw new IllegalArgumentException("Unknown character operation");
        if (model.fields().stream()
                .noneMatch(
                        f -> f.name().equals("character_id") && f.type().equals("uuid") && f.key()))
            throw new IllegalArgumentException("Character UUID ID required");
        var context = PlayerMutations.entityContext(player);
        if (context == null) return admission(false, "IDENTITY_PENDING");
        if (!context.session().toString().equals(session)) return admission(false, "STALE_SESSION");
        List<Row> rows = new ArrayList<>();
        Set<Map<String, Object>> seen = new HashSet<>();
        var iterator = input.iterator();
        while (iterator.advance()) {
            if (rows.size() >= 256) throw new IllegalArgumentException("Too many character rows");
            KahluaTable row = table(iterator.getValue()), values = table(row.rawget("values"));
            // The character key comes exclusively from the authoritative binding.
            if (values.rawget("character_id") != null)
                throw new IllegalArgumentException("Character ID is server managed");
            KahluaTable detached = LuaManager.platform.newTable();
            var fields = values.iterator();
            while (fields.advance()) detached.rawset(fields.getKey(), fields.getValue());
            detached.rawset("character_id", context.character().toString());
            long revision = Long.parseLong(text(row.rawget("revision")));
            if (revision < 0 || revision == Long.MAX_VALUE)
                throw new IllegalArgumentException("Invalid revision");
            if (action.equals("loadOrCreate") && revision != 0)
                throw new IllegalArgumentException("Seeds require revision zero");
            Map<String, Object>
                    copied = copy(model, detached, false, action.equals("patch") && revision > 0),
                    keys = keys(model, copied);
            if (!seen.add(keys)) throw new IllegalArgumentException("Duplicate character row");
            if (action.equals("patch") && revision > 0 && copied.size() == keys.size())
                throw new IllegalArgumentException("Patch fields required");
            rows.add(new Row(copied, revision));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Character rows required");
        List<Row> snapshot = List.copyOf(rows);
        return admit(
                true,
                connection ->
                        EntityDatabase.character(connection, model, action, context, snapshot));
    }

    private static Map<String, Object> keys(Model model, Map<String, Object> values) {
        Map<String, Object> keys = new LinkedHashMap<>();
        for (Field field : model.keys()) {
            Object key = values.get(field.name());
            if (key == null)
                throw new IllegalArgumentException("Missing ID field: " + field.name());
            keys.put(field.name(), key);
        }
        return Map.copyOf(keys);
    }

    private Object define(String name, KahluaTable declaration) {
        if (name.length() > 128 || name.isBlank())
            throw new IllegalArgumentException("Invalid entity name");
        String physical = identifier(text(declaration.rawget("table")));
        List<Field> fields = new ArrayList<>();
        var iterator = table(declaration.rawget("fields")).iterator();
        while (iterator.advance()) {
            if (fields.size() >= 32) throw new IllegalArgumentException("Too many fields");
            String field = identifier(text(iterator.getKey()));
            KahluaTable spec = table(iterator.getValue());
            String type = text(spec.rawget("type"));
            if (!Set.of("string", "text", "uuid", "number", "integer", "boolean").contains(type))
                throw new IllegalArgumentException("Unsupported field type");
            fields.add(new Field(field, type, Boolean.TRUE.equals(spec.rawget("id"))));
        }
        fields.sort(Comparator.comparing(Field::name));
        boolean appendOnly = Boolean.TRUE.equals(declaration.rawget("appendOnly"));
        if (physical.equals("backend_message") && !appendOnly)
            throw new IllegalArgumentException("Backend messages are append only");
        Model model = new Model(name, physical, List.copyOf(fields), appendOnly);
        if (model.keys().isEmpty() || model.keys().size() > 4)
            throw new IllegalArgumentException("One to four ID fields required");
        Model existing = models.get(name);
        if (existing != null) {
            if (!existing.equals(model))
                throw new IllegalArgumentException("Mapping already defined differently");
            return true;
        }
        if (models.size() >= 16
                || models.values().stream().anyMatch(m -> m.table().equals(physical)))
            throw new IllegalArgumentException("Mapping capacity or duplicate table");
        models.put(name, model);
        return true;
    }

    static Object scalar(Field field, Object value) {
        if (value == null) throw new IllegalArgumentException("Missing field: " + field.name());
        return switch (field.type()) {
            case "string" -> {
                String s = text(value);
                if (s.length() > 4096) throw new IllegalArgumentException("String too long");
                yield s;
            }
            case "text" -> {
                String s = text(value);
                if (s.length() > 33_554_432) throw new IllegalArgumentException("Text too long");
                yield s;
            }
            case "uuid" -> UUID.fromString(text(value));
            case "boolean" -> {
                if (!(value instanceof Boolean))
                    throw new IllegalArgumentException("Boolean required");
                yield value;
            }
            case "number", "integer" -> {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
                    throw new IllegalArgumentException("Finite number required");
                double d = ((Number) value).doubleValue();
                if (field.type().equals("integer")) {
                    if (Math.abs(d) > 9007199254740991d || d != Math.rint(d))
                        throw new IllegalArgumentException("Exact integer required");
                    yield (long) d;
                }
                yield d;
            }
            default -> throw new IllegalArgumentException("Unsupported type");
        };
    }

    private static Map<String, Object> copy(Model model, KahluaTable input, boolean keysOnly) {
        return copy(model, input, keysOnly, false);
    }

    private static Map<String, Object> copy(
            Model model, KahluaTable input, boolean keysOnly, boolean partial) {
        Map<String, Object> result = new LinkedHashMap<>();
        var iterator = input.iterator();
        while (iterator.advance()) {
            String name = text(iterator.getKey());
            Field field =
                    model.fields().stream()
                            .filter(f -> f.name().equals(name) && (!keysOnly || f.key()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Unknown field"));
            result.put(name, scalar(field, iterator.getValue()));
        }
        if (!partial)
            for (Field f : keysOnly ? model.keys() : model.fields())
                if (!result.containsKey(f.name()))
                    throw new IllegalArgumentException("Missing field: " + f.name());
        return Collections.unmodifiableMap(result);
    }

    private Object submit(
            String name,
            String action,
            KahluaTable keyTable,
            KahluaTable valuesTable,
            String revision) {
        Model model = models.get(name);
        if (model == null) throw new IllegalArgumentException("Unknown entity");
        if (model.appendOnly() && !Set.of("validate", "find").contains(action))
            throw new IllegalArgumentException("Use message append");
        if (!Set.of("validate", "find", "save", "patch", "delete").contains(action))
            throw new IllegalArgumentException("Unknown operation");
        long expected = Long.parseLong(revision);
        if (expected < 0 || expected == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid revision");
        Map<String, Object> keys =
                action.equals("validate") ? Map.of() : copy(model, keyTable, true);
        Map<String, Object> values =
                action.equals("save")
                        ? copy(model, valuesTable, false)
                        : action.equals("patch") ? copy(model, valuesTable, false, true) : Map.of();
        if (action.equals("patch")
                && (expected == 0
                        || values.isEmpty()
                        || model.keys().stream().anyMatch(f -> values.containsKey(f.name()))))
            throw new IllegalArgumentException(
                    "Existing revision and non-ID patch fields required");
        if (action.equals("save"))
            for (Field key : model.keys())
                if (!keys.get(key.name()).equals(values.get(key.name())))
                    throw new IllegalArgumentException("Entity ID changed");
        return admit(
                !Set.of("find", "validate").contains(action),
                connection ->
                        EntityDatabase.row(connection, model, action, keys, values, expected));
    }

    /**
     * Detached collection read scoped by engine configuration, never by client/Lua supplied IDs.
     */
    private Object listForServer(String name) {
        Model model = models.get(name);
        if (model == null || model.appendOnly())
            throw new IllegalArgumentException("Collection mapping required");
        if (model.keys().stream()
                        .noneMatch(f -> f.name().equals("server_id") && f.type().equals("integer"))
                || model.keys().stream()
                        .noneMatch(f -> f.name().equals("world") && f.type().equals("string")))
            throw new IllegalArgumentException("Server/world ID fields required");
        long server = serverId();
        String world = world();
        if (world.isBlank() || world.length() > 4096)
            throw new IllegalArgumentException("World required");
        Map<String, Object> scope = Map.of("server_id", server, "world", world);
        return admit(
                false,
                connection ->
                        EntityDatabase.row(connection, model, "listForServer", scope, Map.of(), 0));
    }

    private KahluaTable documentRequest(
            String document, String revision, String mutation, KahluaTable operations) {
        boolean marketPage = operations == null && EntityDatabase.isMarketPage(document);
        if (marketPage ? document.length() > 160 : !document.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid document ID");
        long server = serverId();
        String world = world();
        if (world.isEmpty() || world.length() > 128)
            throw new IllegalArgumentException("Invalid document scope");
        String payload = null;
        if (operations != null) {
            long expected = Long.parseLong(revision);
            if (expected < 0 || expected >= Long.MAX_VALUE - 1)
                throw new IllegalArgumentException("Invalid document revision");
            UUID.fromString(mutation);
            int maximum = expected == 0 ? 100000 : 256;
            int count = 0;
            var iterator = operations.iterator();
            while (iterator.advance()) {
                if (++count > maximum)
                    throw new IllegalArgumentException("Too many document mutations");
                if (!(iterator.getKey() instanceof Number n)
                        || n.doubleValue() != Math.rint(n.doubleValue())
                        || n.doubleValue() < 1
                        || n.doubleValue() > maximum)
                    throw new IllegalArgumentException("Mutation array required");
            }
            // Empty Lua tables encode as {}; the SQL contract expects an array.
            payload = count == 0 ? "[]" : encode(operations);
            if (!payload.startsWith("["))
                throw new IllegalArgumentException("Contiguous mutation array required");
        }
        String captured = payload;
        if (captured == null && EntityDatabase.isMarketListing(document)) {
            return (KahluaTable) lua(worker.submit(false,
                    connection -> EntityDatabase.document(connection, server, world, document, null, null, null),
                    EntityDatabase::prepareMarketDictionaries));
        }
        return admit(
                captured != null,
                connection ->
                        EntityDatabase.document(
                                connection, server, world, document, revision, mutation, captured));
    }

    private static String encode(KahluaTable value) {
        String json = jsonValue(value, new IdentityHashMap<>(), 0).toString();
        if (json.length() > 33_554_432) throw new IllegalArgumentException("JSON too long");
        return json;
    }

    private static Object jsonValue(
            Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        if (value instanceof KahluaTable t) {
            if (seen.put(t, true) != null) throw new IllegalArgumentException("Cyclic JSON");
            Map<String, Object> fields = new TreeMap<>();
            SortedMap<Integer, Object> array = new TreeMap<>();
            boolean numeric = true;
            var it = t.iterator();
            int size = 0;
            while (it.advance()) {
                if (++size > 100000)
                    throw new IllegalArgumentException("JSON collection too large");
                Object key = it.getKey(), v = jsonValue(it.getValue(), seen, depth + 1);
                if (key instanceof Number n
                        && n.doubleValue() == Math.rint(n.doubleValue())
                        && n.doubleValue() >= 1
                        && n.doubleValue() <= 100000) array.put(n.intValue(), v);
                else if (key instanceof String s) {
                    numeric = false;
                    fields.put(s, v);
                } else throw new IllegalArgumentException("JSON key type");
            }
            seen.remove(t);
            if (numeric && !array.isEmpty() && array.lastKey() == array.size()) {
                org.json.JSONArray result = new org.json.JSONArray();
                array.values().forEach(result::put);
                return result;
            }
            if (!array.isEmpty()) throw new IllegalArgumentException("Sparse/mixed JSON array");
            return new org.json.JSONObject(fields);
        }
        if (value == null) return org.json.JSONObject.NULL;
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n
                && Double.isFinite(n.doubleValue())
                && Math.abs(n.doubleValue()) <= 9007199254740991d) return value;
        throw new IllegalArgumentException("JSON value type");
    }

    private static Object decode(String text) {
        if (text.length() > 33_554_432) throw new IllegalArgumentException("JSON too long");
        org.json.JSONTokener parser = new org.json.JSONTokener(text);
        Object value = parser.nextValue();
        if (!(value instanceof org.json.JSONObject) && !(value instanceof org.json.JSONArray)
                || parser.nextClean() != 0)
            throw new IllegalArgumentException("JSON document required");
        return fromJson(value, 0);
    }

    private static Object fromJson(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON nesting limit");
        if (value instanceof org.json.JSONObject object) {
            if (object.length() > 100000)
                throw new IllegalArgumentException("JSON collection too large");
            KahluaTable result = LuaManager.platform.newTable();
            for (String key : object.keySet())
                result.rawset(key, fromJson(object.get(key), depth + 1));
            return result;
        }
        if (value instanceof org.json.JSONArray array) {
            if (array.length() > 100000)
                throw new IllegalArgumentException("JSON collection too large");
            KahluaTable result = LuaManager.platform.newTable();
            for (int i = 0; i < array.length(); i++)
                result.rawset((double) i + 1, fromJson(array.get(i), depth + 1));
            return result;
        }
        if (value instanceof Number n
                && (!Double.isFinite(n.doubleValue())
                        || Math.abs(n.doubleValue()) > 9007199254740991d))
            throw new IllegalArgumentException("JSON numeric range");
        return value == org.json.JSONObject.NULL ? null : lua(value);
    }

    private static Object lua(Object value) {
        if (value instanceof UUID id) return id.toString();
        if (value instanceof List<?> list) {
            KahluaTable table = LuaManager.platform.newTable();
            for (int i = 0; i < list.size(); i++) table.rawset((double) (i + 1), lua(list.get(i)));
            return table;
        }
        if (value instanceof Map<?, ?> map) {
            KahluaTable table = LuaManager.platform.newTable();
            map.forEach((k, v) -> table.rawset(k, lua(v)));
            return table;
        }
        return value instanceof Number n ? n.doubleValue() : value;
    }
}
