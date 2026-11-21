package cloud.apocalipse.pz.agent.config;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record AgentConfiguration(String profile, URI telemetryEndpoint, URI restartEndpoint, boolean restartEnabled) {
    /** NPC engine: JVM argument {@code -Dapocbr.npc.enabled=true}; actor/planner hooks, default false. */
    public static final String NPC_ENABLED_PROPERTY = "apocbr.npc.enabled";
    /** NPC engine: JVM argument {@code -Dapocbr.npc.async=false}; default true, detached planning only. */
    public static final String NPC_ASYNC_PROPERTY = "apocbr.npc.async";

    public static NpcSettings npcSettings() {
        return new NpcSettings(Boolean.parseBoolean(systemProperty(NPC_ENABLED_PROPERTY, "false")),
            Boolean.parseBoolean(systemProperty(NPC_ASYNC_PROPERTY, "true")));
    }

    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.enabled=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_ENABLED = "apocbr.guide.enabled";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.facing=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_FACING = "apocbr.guide.facing";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.female=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_FEMALE = "apocbr.guide.female";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.name=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_NAME = "apocbr.guide.name";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.outfit=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_OUTFIT = "apocbr.guide.outfit";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.profile=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_PROFILE = "apocbr.guide.profile";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.trade.enabled=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_TRADE_ENABLED = "apocbr.guide.trade.enabled";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.x=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_X = "apocbr.guide.x";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.y=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_Y = "apocbr.guide.y";
    /** NPC engine: supported JVM argument {@code -Dapocbr.guide.z=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_GUIDE_Z = "apocbr.guide.z";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.combat.testWeapon=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_COMBAT_TESTWEAPON = "apocbr.npc.combat.testWeapon";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.language=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_LANGUAGE = "apocbr.npc.language";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.logLevel=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_LOGLEVEL = "apocbr.npc.logLevel";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.persistence.checkpointMs=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_PERSISTENCE_CHECKPOINTMS = "apocbr.npc.persistence.checkpointMs";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.persistence.enabled=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_PERSISTENCE_ENABLED = "apocbr.npc.persistence.enabled";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.playerHitTrace=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_PLAYERHITTRACE = "apocbr.npc.playerHitTrace";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.serverKey=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_SERVERKEY = "apocbr.npc.serverKey";
    /** NPC engine: supported JVM argument {@code -Dapocbr.npc.zombieControl=...}; legacy-compatible setting. */
    public static final String NPC_ARG_APOCBR_NPC_ZOMBIECONTROL = "apocbr.npc.zombieControl";
    public static boolean booleanProperty(String name) { return Boolean.parseBoolean(systemProperty(name, "false")); }

    public record NpcSettings(boolean enabled, boolean asynchronous) { }
    /** NPC persistence: JVM argument {@code -Dapocbr.npc.journalDirectory=<directory>}; server/world subdirectories are isolated. */
    public static final String NPC_JOURNAL_DIRECTORY_PROPERTY="apocbr.npc.journalDirectory";
    /** NPC actor runtime: JVM argument {@code -Dapocbr.npc.actors.enabled=false}; default true when NPC module is enabled. */
    public static final String NPC_ACTORS_ENABLED_PROPERTY="apocbr.npc.actors.enabled";
    public static boolean npcActorsEnabled(){return Boolean.parseBoolean(systemProperty(NPC_ACTORS_ENABLED_PROPERTY,"true"));}

    /** JVM argument {@code -Dapocbr.profile}; selects the YAML profile loaded by the agent. */
    public static final String PROFILE_PROPERTY = "apocbr.profile";
    /** JVM argument {@code -Dapocbr.telemetry.endpoint}; overrides the telemetry endpoint. */
    public static final String TELEMETRY_ENDPOINT_PROPERTY = "apocbr.telemetry.endpoint";
    /** JVM argument {@code -Dapocbr.telemetry.apiKey}; supplies the telemetry API credential. */
    public static final String TELEMETRY_API_KEY_PROPERTY = "apocbr.telemetry.apiKey";
    /** JVM argument {@code -Dapocbr.telemetry.gameVersion}; overrides the telemetry game-version label. */
    public static final String TELEMETRY_GAME_VERSION_PROPERTY = "apocbr.telemetry.gameVersion";
    /** JVM argument {@code -Dapocbr.restart.enabled}; overrides the YAML restart-notifier setting. */
    public static final String RESTART_ENABLED_PROPERTY = "apocbr.restart.enabled";
    /** JVM argument {@code -Dapocbr.restart.apiKey}; supplies the restart API credential. */
    public static final String RESTART_API_KEY_PROPERTY = "apocbr.restart.apiKey";
    /** JVM argument {@code -Dapocbr.restart.url}; overrides the restart endpoint. */
    public static final String RESTART_URL_PROPERTY = "apocbr.restart.url";
    /** JVM argument {@code -Dapocbr.bridge.serverKey}; shared fallback credential for agent APIs. */
    public static final String BRIDGE_SERVER_KEY_PROPERTY = "apocbr.bridge.serverKey";

    /** PostgreSQL feature: JVM argument {@code -Dapocbr.postgres.enabled=true}. */
    public static final String POSTGRES_ENABLED_PROPERTY = "apocbr.postgres.enabled";
    /** PostgreSQL feature: JVM argument {@code -Dapocbr.postgres.url=jdbc:postgresql://...}. */
    public static final String POSTGRES_URL_PROPERTY = "apocbr.postgres.url";
    /** PostgreSQL feature: JVM argument {@code -Dapocbr.postgres.poolSize=8}; range 1..32. */
    public static final String POSTGRES_POOL_SIZE_PROPERTY = "apocbr.postgres.poolSize";
    /** PostgreSQL account feature: JVM argument {@code -Dapocbr.postgres.serverDatabase.enabled=true}. */
    public static final String POSTGRES_ACCOUNTS_ENABLED_PROPERTY = "apocbr.postgres.serverDatabase.enabled";
    /** PostgreSQL login feature: JVM argument {@code -Dapocbr.postgres.loginWorkflowTimeoutMillis=5000}. */
    public static final String POSTGRES_LOGIN_WORKFLOW_PROPERTY = "apocbr.postgres.loginWorkflowTimeoutMillis";
    /** PostgreSQL login feature: JVM argument {@code -Dapocbr.postgres.loginNetworkTimeoutMillis=3000}. */
    public static final String POSTGRES_LOGIN_NETWORK_PROPERTY = "apocbr.postgres.loginNetworkTimeoutMillis";
    /** PostgreSQL journal feature: JVM argument {@code -Dapocbr.postgres.journalDirectory=<directory>}. */
    public static final String POSTGRES_JOURNAL_DIRECTORY_PROPERTY = "apocbr.postgres.journalDirectory";
    /** PostgreSQL feature: JDBC URL environment override; credentials remain outside YAML. */
    public static final String POSTGRES_URL_ENVIRONMENT = "APOCBR_POSTGRES_URL";

    /** Dictionary setup: {@code -Dapocbr.postgres.dictionary.enabled=true}; defaults to the master flag. */
    public static final String DICTIONARY_ENABLED_PROPERTY = "apocbr.postgres.dictionary.enabled";
    /** Dictionary allocation history: {@code -Dapocbr.postgres.dictionary.saveKey=...}; defaults to gameSaveWorld. */
    public static final String DICTIONARY_SAVE_KEY_PROPERTY = "apocbr.postgres.dictionary.saveKey";
    /** Dictionary namespace: {@code -Dapocbr.postgres.dictionary.namespaceId=<uuid>}; optional explicit history. */
    public static final String DICTIONARY_NAMESPACE_PROPERTY = "apocbr.postgres.dictionary.namespaceId";
    /** Dictionary publication: {@code -Dapocbr.postgres.dictionary.publicationTimeoutSeconds=30}; range 5..300. */
    public static final String DICTIONARY_PUBLICATION_TIMEOUT_PROPERTY = "apocbr.postgres.dictionary.publicationTimeoutSeconds";

    public record DictionarySettings(boolean enabled, int serverId, String saveKey,
                                     java.util.UUID namespace, int publicationTimeoutSeconds) { }

    /** Historical native decoding: {@code -Dapocbr.postgres.dictionary.decode.enabled=true}; defaults on with dictionary setup. */
    public static final String DICTIONARY_DECODE_ENABLED_PROPERTY = "apocbr.postgres.dictionary.decode.enabled";

    public static boolean dictionaryDecodeEnabled() {
        return dictionarySettings().enabled() && Boolean.parseBoolean(systemProperty(DICTIONARY_DECODE_ENABLED_PROPERTY, "true"));
    }

    /** Player reconstruction: {@code -Dapocbr.postgres.playerRelational.maxGraphMiB=128}; range 1..512. */
    public static final String PLAYER_GRAPH_MIB_PROPERTY = "apocbr.postgres.playerRelational.maxGraphMiB";
    /** Player reconstruction: {@code -Dapocbr.postgres.playerRelational.maxRows=500000}; range 1..5000000. */
    public static final String PLAYER_ROWS_PROPERTY = "apocbr.postgres.playerRelational.maxRows";
    /** Player profile encoding: {@code -Dapocbr.postgres.playerRelational.maxSourceMiB=8}; range 1..64. */
    public static final String PLAYER_SOURCE_MIB_PROPERTY = "apocbr.postgres.playerRelational.maxSourceMiB";
    public static int playerGraphBytes() { return Math.clamp(integerProperty(System.getProperties(), PLAYER_GRAPH_MIB_PROPERTY, 128), 1, 512) * 1024 * 1024; }
    public static int playerMaxRows() { return Math.clamp(integerProperty(System.getProperties(), PLAYER_ROWS_PROPERTY, 500_000), 1, 5_000_000); }
    public static int playerSourceBytes() { return Math.clamp(integerProperty(System.getProperties(), PLAYER_SOURCE_MIB_PROPERTY, 8), 1, 64) * 1024 * 1024; }

    /** Player capture: {@code -Dapocbr.postgres.playerRelational.maxSnapshotMiB=32}; range 1..128. */
    public static final String PLAYER_SNAPSHOT_MIB_PROPERTY = "apocbr.postgres.playerRelational.maxSnapshotMiB";
    /** Player capture queue: {@code -Dapocbr.postgres.playerRelational.queuedMiB=64}; range 1..512. */
    public static final String PLAYER_QUEUE_MIB_PROPERTY = "apocbr.postgres.playerRelational.queuedMiB";
    /** Player capture: {@code -Dapocbr.postgres.playerRelational.maxNodes=1000000}; range 1..5000000. */
    public static final String PLAYER_NODES_PROPERTY = "apocbr.postgres.playerRelational.maxNodes";
    /** Player graph transaction: {@code -Dapocbr.postgres.playerRelational.importWriteBudgetMs=60000}; range 1000..300000. */
    public static final String PLAYER_WRITE_BUDGET_PROPERTY = "apocbr.postgres.playerRelational.importWriteBudgetMs";
    /** Player delta cache: {@code -Dapocbr.postgres.playerRelational.mutations.cacheMiB=64}; range 1..512. */
    public static final String PLAYER_MUTATION_CACHE_PROPERTY = "apocbr.postgres.playerRelational.mutations.cacheMiB";
    /** Player scalar sampling: {@code -Dapocbr.postgres.playerRelational.mutations.sampleMs=30000}; range 1000..60000. */
    public static final String PLAYER_SAMPLE_PROPERTY = "apocbr.postgres.playerRelational.mutations.sampleMs";
    /** Player graph sampling: {@code -Dapocbr.postgres.playerRelational.mutations.reconcileMs=30000}; range 1000..60000. */
    public static final String PLAYER_RECONCILE_MILLIS_PROPERTY = "apocbr.postgres.playerRelational.mutations.reconcileMs";
    /** Player graph reconciliation: {@code -Dapocbr.postgres.playerRelational.mutations.reconcile=true}; required for player authority. */
    public static final String PLAYER_RECONCILE_PROPERTY = "apocbr.postgres.playerRelational.mutations.reconcile";
    /** Player live sampling: {@code -Dapocbr.postgres.playerRelational.mutations.enabled=true}; required for player authority. */
    public static final String PLAYER_MUTATIONS_PROPERTY = "apocbr.postgres.playerRelational.mutations.enabled";
    /** Player authority: {@code -Dapocbr.postgres.players.enabled=true}; defaults false. */
    public static final String PLAYER_ENABLED_PROPERTY = "apocbr.postgres.players.enabled";
    /** Player creation dataset: {@code -Dapocbr.postgres.playerRelational.sourceDatasetId=<uuid>}; defaults to the legacy save-path identity. */
    public static final String PLAYER_DATASET_PROPERTY = "apocbr.postgres.playerRelational.sourceDatasetId";
    /** Player durable journal: {@code -Dapocbr.postgres.playerJournal.directory=<path>}; defaults below the configured PostgreSQL journal. */
    public static final String PLAYER_JOURNAL_PROPERTY = "apocbr.postgres.playerJournal.directory";
    /** Player creation: {@code -Dapocbr.postgres.createdPlayerTimeoutMillis=30000}; range 1000..120000. */
    public static final String PLAYER_CREATION_TIMEOUT_PROPERTY = "apocbr.postgres.createdPlayerTimeoutMillis";

    public static boolean playerEnabled() { return Boolean.parseBoolean(systemProperty(POSTGRES_ENABLED_PROPERTY, "false")) && Boolean.parseBoolean(systemProperty(PLAYER_ENABLED_PROPERTY, "false")); }
    public static boolean playerMutationsEnabled() { return Boolean.parseBoolean(systemProperty(PLAYER_MUTATIONS_PROPERTY, "true")); }
    public static boolean playerReconcileEnabled() { return Boolean.parseBoolean(systemProperty(PLAYER_RECONCILE_PROPERTY, "true")); }
    public static long playerMutationCacheMiB() { return integerProperty(System.getProperties(), PLAYER_MUTATION_CACHE_PROPERTY, 64); }
    public static long playerSampleMillis() { return integerProperty(System.getProperties(), PLAYER_SAMPLE_PROPERTY, 30000); }
    public static long playerReconcileMillis() { return integerProperty(System.getProperties(), PLAYER_RECONCILE_MILLIS_PROPERTY, 30000); }
    public static int playerCreationTimeoutMillis() { return Math.clamp(integerProperty(System.getProperties(), PLAYER_CREATION_TIMEOUT_PROPERTY, 30000), 1000, 120000); }
    public static int playerLimit(String name, int fallback) {
        String property = switch (name) {
            case "maxGraphMiB" -> PLAYER_GRAPH_MIB_PROPERTY;
            case "maxSnapshotMiB" -> PLAYER_SNAPSHOT_MIB_PROPERTY;
            case "maxSourceMiB" -> PLAYER_SOURCE_MIB_PROPERTY;
            case "queuedMiB" -> PLAYER_QUEUE_MIB_PROPERTY;
            case "maxRows" -> PLAYER_ROWS_PROPERTY;
            case "maxNodes" -> PLAYER_NODES_PROPERTY;
            case "importWriteBudgetMs" -> PLAYER_WRITE_BUDGET_PROPERTY;
            default -> throw new IllegalArgumentException("Unknown player limit");
        };
        return integerProperty(System.getProperties(), property, fallback);
    }

    public static DictionarySettings dictionarySettings() {
        boolean enabled = Boolean.parseBoolean(systemProperty(POSTGRES_ENABLED_PROPERTY, "false"))
            && Boolean.parseBoolean(systemProperty(DICTIONARY_ENABLED_PROPERTY, "true"));
        if (!enabled) return new DictionarySettings(false, 0, null, null, 30);
        int server = integerProperty(System.getProperties(), APOCBR_POSTGRES_SERVER_ID_PROPERTY, 0);
        int timeout = integerProperty(System.getProperties(), DICTIONARY_PUBLICATION_TIMEOUT_PROPERTY, 30);
        if (timeout < 5 || timeout > 300) throw new IllegalArgumentException("Dictionary publication timeout must be 5..300 seconds");
        String save = systemProperty(DICTIONARY_SAVE_KEY_PROPERTY);
        if (save != null && (save.isBlank() || save.length() > 128)) throw new IllegalArgumentException("Dictionary save key must have 1..128 characters");
        String namespace = systemProperty(DICTIONARY_NAMESPACE_PROPERTY);
        return new DictionarySettings(true, server, save,
            namespace == null ? null : java.util.UUID.fromString(namespace), timeout);
    }

    public static PostgresSettings postgresSettings() {
        return postgresSettings(System.getProperties(), System.getenv());
    }

    static PostgresSettings postgresSettings(java.util.Properties properties, Map<String, String> environment) {
        boolean enabled = Boolean.parseBoolean(properties.getProperty(POSTGRES_ENABLED_PROPERTY, "false"));
        if (!enabled) {
            return new PostgresSettings(false, false, null, 8, 5000, 3000);
        }
        String url = environment.get(POSTGRES_URL_ENVIRONMENT);
        if (url == null || url.isBlank()) url = properties.getProperty(POSTGRES_URL_PROPERTY);
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL requires a JDBC PostgreSQL URL");
        }
        int size = integerProperty(properties, POSTGRES_POOL_SIZE_PROPERTY, 8);
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("PostgreSQL pool size must be between 1 and 32");
        }
        return new PostgresSettings(true,
            Boolean.parseBoolean(properties.getProperty(POSTGRES_ACCOUNTS_ENABLED_PROPERTY, "true")),
            url, size,
            Math.clamp(integerProperty(properties, POSTGRES_LOGIN_WORKFLOW_PROPERTY, 5000), 500, 30000),
            Math.clamp(integerProperty(properties, POSTGRES_LOGIN_NETWORK_PROPERTY, 3000), 250, 30000));
    }

    private static int integerProperty(java.util.Properties properties, String name, int fallback) {
        String value = properties.getProperty(name);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid integer setting: " + name);
        }
    }

    public record PostgresSettings(boolean enabled, boolean accountsEnabled, String jdbcUrl,
                                   int poolSize, int loginWorkflowMillis, int loginNetworkMillis) {
        @Override
        public String toString() {
            return "PostgresSettings[enabled=" + enabled + ", accountsEnabled=" + accountsEnabled
                + ", jdbcUrl=<redacted>, poolSize=" + poolSize + "]";
        }
    }

    /** Telemetry endpoint environment override; takes precedence over its corresponding JVM property. */
    public static final String TELEMETRY_ENDPOINT_ENVIRONMENT = "APOCBR_TELEMETRY_ENDPOINT";
    /** Telemetry API credential environment override; takes precedence over its corresponding JVM property. */
    public static final String TELEMETRY_API_KEY_ENVIRONMENT = "APOCBR_TELEMETRY_API_KEY";
    /** Restart API credential environment override, used before the shared bridge credential. */
    public static final String RESTART_API_KEY_ENVIRONMENT = "PZMANAGER_MOD_UPDATE_TOKEN";
    /** Restart endpoint environment fallback, overridden by the corresponding JVM property. */
    public static final String RESTART_URL_ENVIRONMENT = "PZMANAGER_RESTART_URL";

    public static AgentConfiguration load() {
        return load(systemProperty(PROFILE_PROPERTY, "prod"));
    }

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.api.enabled=...}; startup setting. */
    public static final String APOCBR_BRIDGE_API_ENABLED_PROPERTY = "apocbr.bridge.api.enabled";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.api.requestIntervalMs=...}; startup setting. */
    public static final String APOCBR_BRIDGE_API_REQUEST_INTERVAL_MS_PROPERTY = "apocbr.bridge.api.requestIntervalMs";

    /** Server Lua feature: JVM argument {@code -Dapocbr.events.enabled=...}; startup setting. */
    public static final String APOCBR_EVENTS_ENABLED_PROPERTY = "apocbr.events.enabled";

    /** Server Lua feature: JVM argument {@code -Dapocbr.events.serverKey=...}; startup setting. */
    public static final String APOCBR_EVENTS_SERVER_KEY_PROPERTY = "apocbr.events.serverKey";

    /** Server Lua feature: JVM argument {@code -Dapocbr.events.pollMillis=...}; startup setting. */
    public static final String APOCBR_EVENTS_POLL_MILLIS_PROPERTY = "apocbr.events.pollMillis";

    /** Server Lua feature: JVM argument {@code -Dapocbr.events.freshMillis=...}; startup setting. */
    public static final String APOCBR_EVENTS_FRESH_MILLIS_PROPERTY = "apocbr.events.freshMillis";

    /** Server Lua feature: JVM argument {@code -Dapocbr.events.displayZone=...}; startup setting. */
    public static final String APOCBR_EVENTS_DISPLAY_ZONE_PROPERTY = "apocbr.events.displayZone";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.endpoint=...}; startup setting. */
    public static final String APOCBR_BRIDGE_ENDPOINT_PROPERTY = "apocbr.bridge.endpoint";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.apiKey=...}; startup setting. */
    public static final String APOCBR_BRIDGE_API_KEY_PROPERTY = "apocbr.bridge.apiKey";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.connectTimeoutMs=...}; startup setting. */
    public static final String APOCBR_BRIDGE_CONNECT_TIMEOUT_MS_PROPERTY = "apocbr.bridge.connectTimeoutMs";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.requestTimeoutMs=...}; startup setting. */
    public static final String APOCBR_BRIDGE_REQUEST_TIMEOUT_MS_PROPERTY = "apocbr.bridge.requestTimeoutMs";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.requestIntervalMs=...}; startup setting. */
    public static final String APOCBR_BRIDGE_REQUEST_INTERVAL_MS_PROPERTY = "apocbr.bridge.requestIntervalMs";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.logDelivery=...}; startup setting. */
    public static final String APOCBR_BRIDGE_LOG_DELIVERY_PROPERTY = "apocbr.bridge.logDelivery";

    /** Radio HTTP feature: endpoint environment override. */
    public static final String BRIDGE_ENDPOINT_ENVIRONMENT = "APOCBR_BRIDGE_ENDPOINT";
    /** Radio HTTP feature: credential environment override. */
    public static final String BRIDGE_API_KEY_ENVIRONMENT = "APOCBR_BRIDGE_API_KEY";
    /** Server Lua subsystem master switch; JVM argument {@code -Dapocbr.lua.enabled=false}. */
    public static final String LUA_ENABLED_PROPERTY = "apocbr.lua.enabled";

    public static boolean luaEnabled() { return Boolean.parseBoolean(systemProperty(LUA_ENABLED_PROPERTY, "true")); }

    public static long longProperty(String name, long fallback) {
        String value = systemProperty(name);
        if (value == null) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid integer setting: " + name); }
    }

    /** Server Lua feature: JVM argument {@code -Dapocbr.postgres.loginHistory.journalDirectory=...}; startup setting. */
    public static final String APOCBR_POSTGRES_LOGIN_HISTORY_JOURNAL_DIRECTORY_PROPERTY = "apocbr.postgres.loginHistory.journalDirectory";

    /** Shared Lua/dictionary identity: {@code -Dapocbr.postgres.serverId=...}; registered positive player_rel.server.id. */
    public static final String APOCBR_POSTGRES_SERVER_ID_PROPERTY = "apocbr.postgres.serverId";

    /** Server Lua feature: JVM argument {@code -Dapocbr.postgres.loginHistory.enabled=...}; startup setting. */
    public static final String APOCBR_POSTGRES_LOGIN_HISTORY_ENABLED_PROPERTY = "apocbr.postgres.loginHistory.enabled";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.enabled=...}; startup setting. */
    public static final String APOCBR_BRIDGE_ENABLED_PROPERTY = "apocbr.bridge.enabled";

    /** Server Lua feature: JVM argument {@code -Dapocbr.bridge.captureOnly=...}; startup setting. */
    public static final String APOCBR_BRIDGE_CAPTURE_ONLY_PROPERTY = "apocbr.bridge.captureOnly";

    /** Account workers: JVM argument {@code -Dapocbr.postgres.async.enabled=false}; defaults true with account hooks. */
    public static final String ASYNC_ACCOUNTS_ENABLED_PROPERTY="apocbr.postgres.async.enabled";
    public static boolean asyncAccountsEnabled() {
        return Boolean.parseBoolean(systemProperty(POSTGRES_ENABLED_PROPERTY,"false"))
            && Boolean.parseBoolean(systemProperty(POSTGRES_ACCOUNTS_ENABLED_PROPERTY,"true"))
            && Boolean.parseBoolean(systemProperty(ASYNC_ACCOUNTS_ENABLED_PROPERTY,"true"));
    }
    public static String systemProperty(String name) {
        return System.getProperty(name);
    }

    public static String systemProperty(String name, String defaultValue) {
        return System.getProperty(name, defaultValue);
    }

    public static String environmentVariable(String name) {
        return System.getenv(name);
    }

    public static String environmentOrProperty(String propertyName, String environmentName) {
        String value = environmentVariable(environmentName);
        return value == null || value.isBlank() ? systemProperty(propertyName) : value;
    }

    static AgentConfiguration load(String requestedProfile) {
        String profile = requestedProfile == null ? "" : requestedProfile.trim();
        if (!"prod".equals(profile) && !"local".equals(profile)) {
            throw new IllegalArgumentException("Unsupported ApocBR profile: " + profile);
        }

        Map<?, ?> baseline = loadDocument("application.yaml");
        Map<?, ?> local = "local".equals(profile) ? loadDocument("application-local.yaml") : Map.of();

        return new AgentConfiguration(
            profile,
            endpoint(local, baseline, "telemetry", "base-url", "/api/v1/telemetry"),
            endpoint(local, baseline, "restart", "base-url", "/api/server/mod-update/restart"),
            booleanSetting(local, baseline, "restart", "enabled")
        );
    }

    private static URI endpoint(Map<?, ?> override, Map<?, ?> baseline, String section, String name, String path) {
        return URI.create(setting(override, baseline, section, name)).resolve(path);
    }

    private static String setting(Map<?, ?> override, Map<?, ?> baseline, String section, String name) {
        Object value = nested(override, section, name);
        if (value == null) {
            value = nested(baseline, section, name);
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Missing configuration value: apocbr." + section + "." + name);
        }
        return text;
    }

    private static boolean booleanSetting(Map<?, ?> override, Map<?, ?> baseline, String section, String name) {
        Object value = nested(override, section, name);
        if (value == null) {
            value = nested(baseline, section, name);
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalStateException("Missing or invalid configuration value: apocbr." + section + "." + name);
        }
        return result;
    }

    private static Object nested(Map<?, ?> root, String section, String name) {
        Object apocbr = root.get("apocbr");
        if (!(apocbr instanceof Map<?, ?> apocbrValues)) {
            return null;
        }
        Object sectionValue = apocbrValues.get(section);
        if (!(sectionValue instanceof Map<?, ?> sectionValues)) {
            return null;
        }
        return sectionValues.get(name);
    }

    private static Map<?, ?> loadDocument(String resourceName) {
        try (InputStream input = AgentConfiguration.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing configuration resource: " + resourceName);
            }
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(value instanceof Map<?, ?> values)) {
                throw new IllegalStateException("Invalid configuration resource: " + resourceName);
            }
            return values;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to read configuration resource: " + resourceName, failure);
        }
    }
}
