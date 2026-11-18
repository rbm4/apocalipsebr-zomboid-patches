package cloud.apocalipse.pz.agent.config;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record AgentConfiguration(String profile, URI telemetryEndpoint, URI restartEndpoint, boolean restartEnabled) {
    private static final String PROFILE_PROPERTY = "apocbr.profile";

    public static AgentConfiguration load() {
        return load(System.getProperty(PROFILE_PROPERTY, "prod"));
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
            URI.create(setting(local, baseline, "telemetry", "endpoint")),
            URI.create(setting(local, baseline, "restart", "endpoint")),
            booleanSetting(local, baseline, "restart", "enabled")
        );
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
