package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Explicit task configuration forwarding; plain integration runs retain isolated defaults. */
final class IntegrationLaunchSettings {
    static final String TASK_CONFIGURATION = "pz.it.useTaskConfiguration";

    private IntegrationLaunchSettings() { }

    static Map<String, String> properties(Properties runner, Path run, String localEndpoint) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put(AgentConfiguration.PROFILE_PROPERTY, "local");
        settings.put(AgentConfiguration.POSTGRES_ENABLED_PROPERTY, "false");
        settings.put(AgentConfiguration.RESTART_ENABLED_PROPERTY, "false");
        if (Boolean.parseBoolean(runner.getProperty(TASK_CONFIGURATION, "false"))) {
            runner.stringPropertyNames().stream().filter(key -> key.startsWith("apocbr.")).sorted()
                .forEach(key -> settings.put(key, runner.getProperty(key)));
        } else {
            settings.put(AgentConfiguration.TELEMETRY_ENDPOINT_PROPERTY, localEndpoint + "/api/v1/telemetry");
            settings.put(AgentConfiguration.TELEMETRY_API_KEY_PROPERTY, "integration-only");
            settings.put(AgentConfiguration.RESTART_URL_PROPERTY, localEndpoint + "/restart");
        }
        // Journal/replay state is owned by this run even when task service settings are reused.
        settings.put(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY, run.resolve("journal").toString());
        return settings;
    }

    static void configureEnvironment(Map<String, String> child, Map<String, String> runner, Properties properties) {
        child.keySet().removeIf(key -> {
            String upper = key.toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("APOCBR_") || upper.startsWith("PZMANAGER_")
                || java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").contains(upper);
        });
        if (Boolean.parseBoolean(properties.getProperty(TASK_CONFIGURATION, "false"))) {
            for (String key : java.util.List.of(AgentConfiguration.POSTGRES_URL_ENVIRONMENT,
                    AgentConfiguration.TELEMETRY_ENDPOINT_ENVIRONMENT, AgentConfiguration.TELEMETRY_API_KEY_ENVIRONMENT,
                    AgentConfiguration.RESTART_API_KEY_ENVIRONMENT, AgentConfiguration.RESTART_URL_ENVIRONMENT,
                    "APOCBR_BRIDGE_API_KEY")) {
                if (runner.containsKey(key)) child.put(key, runner.get(key));
            }
        }
    }
}
