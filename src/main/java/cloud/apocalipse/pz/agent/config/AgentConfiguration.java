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
