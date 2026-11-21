package cloud.apocalipse.pz.agent.config;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record AgentConfiguration(String profile, URI telemetryEndpoint, URI restartEndpoint, boolean restartEnabled) {
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
