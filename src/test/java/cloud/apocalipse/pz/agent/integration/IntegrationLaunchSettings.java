package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.experimental.worldsimulation.WorldSimulation;
import cloud.apocalipse.pz.agent.integration.probe.StreamingWorkload;
import cloud.apocalipse.pz.agent.integration.probe.MovingObjectLoad;
import cloud.apocalipse.pz.agent.integration.probe.EntityAllocations;
import cloud.apocalipse.pz.agent.integration.probe.UnloadBreakdownProbe;
import cloud.apocalipse.pz.agent.integration.probe.UnloadSamplingProbe;
import cloud.apocalipse.pz.agent.integration.probe.BucketUnloadProbe;
import cloud.apocalipse.pz.agent.integration.probe.ChunkLifecycleProbe;
import cloud.apocalipse.pz.agent.integration.probe.VehicleCadenceProbe;
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
        boolean streaming = Boolean.parseBoolean(runner.getProperty("pz.it.streaming", "false"));
        boolean moving = Boolean.parseBoolean(runner.getProperty("pz.it.movingObjects", "false"));
        if (Boolean.parseBoolean(runner.getProperty(MovingObjectLoad.PROPERTY))) {
            if (!streaming || !"players30".equals(runner.getProperty(StreamingWorkload.PROFILE)) || moving
                    || runner.getProperty("pz.it.streamingBaseline") != null) {
                throw new IllegalArgumentException("Moving-object load requires its own players30 streaming run");
            }
            MovingObjectLoad.Settings.from(runner).forward(settings);
            if (Boolean.parseBoolean(runner.getProperty(EntityAllocations.PROPERTY))) {
                settings.put(EntityAllocations.PROPERTY, "true");
            }
            settings.put(AgentConfiguration.MOVING_OBJECT_TELEMETRY_PROPERTY, "true");
        }
        if (Boolean.parseBoolean(runner.getProperty(WorldSimulation.PROPERTY))) {
            if (!streaming || !"vehicles72".equals(runner.getProperty(StreamingWorkload.PROFILE))
                    || Boolean.parseBoolean(runner.getProperty(VehicleCadenceProbe.COMPARE_PROPERTY))
                    || Boolean.parseBoolean(runner.getProperty(ChunkLifecycleProbe.COMPARE_PROPERTY))) {
                throw new IllegalArgumentException("Experimental world simulation requires its own vehicles72 streaming run");
            }
            settings.put(WorldSimulation.PROPERTY, "true");
        }
        if (Boolean.parseBoolean(runner.getProperty(ChunkLifecycleProbe.COMPARE_PROPERTY))
                && (!streaming || !"vehicles72".equals(runner.getProperty(StreamingWorkload.PROFILE)))) {
            throw new IllegalArgumentException("Chunk comparison requires streaming and the vehicles72 profile");
        }
        if (moving && runner.getProperty("pz.it.streamingBaseline") != null) {
            throw new IllegalArgumentException("The one-cycle moving-object scenario has no warm streaming baseline");
        }
        if (Boolean.parseBoolean(runner.getProperty(UnloadBreakdownProbe.PROPERTY))) {
            if (!streaming) throw new IllegalArgumentException("Unload breakdown requires the streaming fixture");
            settings.put(UnloadBreakdownProbe.PROPERTY, "true");
        }
        boolean sampleUnload = Boolean.parseBoolean(runner.getProperty(UnloadSamplingProbe.PROPERTY));
        boolean countSearches = Boolean.parseBoolean(runner.getProperty(UnloadSamplingProbe.SEARCH_PROPERTY));
        if (sampleUnload) {
            if (!streaming) throw new IllegalArgumentException("Unload sampling requires the streaming fixture");
            settings.put(UnloadSamplingProbe.PROPERTY, "true");
        }
        if (countSearches) {
            if (!sampleUnload) throw new IllegalArgumentException("Native search accounting requires unload sampling");
            settings.put(UnloadSamplingProbe.SEARCH_PROPERTY, "true");
        }
        if (Boolean.parseBoolean(runner.getProperty(BucketUnloadProbe.PROPERTY))) {
            if (!sampleUnload || !countSearches || !Boolean.parseBoolean(
                    runner.getProperty(AgentConfiguration.ENTITY_SIMULATION_OPTIMIZATION, "true"))) {
                throw new IllegalArgumentException("Bucket profiling requires unload sampling, native search counting and entity simulation hooks");
            }
            settings.put(BucketUnloadProbe.PROPERTY, "true");
        }
        if (moving && !streaming) {
            throw new IllegalArgumentException("The moving-object scenario requires -Dpz.it.streaming=true");
        }
        boolean configured = Boolean.parseBoolean(runner.getProperty(TASK_CONFIGURATION, "false"));
        if (streaming && configured) {
            throw new IllegalArgumentException("The streaming scenario requires the isolated server fixture");
        }
        settings.put(AgentConfiguration.PROFILE_PROPERTY, "local");
        settings.put(AgentConfiguration.POSTGRES_ENABLED_PROPERTY, "false");
        settings.put(AgentConfiguration.RESTART_ENABLED_PROPERTY, "false");
        if (configured) {
            runner.stringPropertyNames().stream().filter(key -> key.startsWith("apocbr.")).sorted()
                .forEach(key -> settings.put(key, runner.getProperty(key)));
        } else {
            settings.put(AgentConfiguration.TELEMETRY_ENDPOINT_PROPERTY, localEndpoint + "/api/v1/telemetry");
            settings.put(AgentConfiguration.TELEMETRY_API_KEY_PROPERTY, "integration-only");
            settings.put(AgentConfiguration.RESTART_URL_PROPERTY, localEndpoint + "/restart");
        }
        if (streaming) {
            var workload = StreamingWorkload.from(runner);
            if (workload.chunkComparison()) {
                if (Boolean.parseBoolean(runner.getProperty(VehicleCadenceProbe.COMPARE_PROPERTY))
                        || runner.getProperty("pz.it.streamingBaseline") != null
                        || !Boolean.parseBoolean(runner.getProperty(AgentConfiguration.CHUNK_CONTAINER_OPTIMIZATION, "true"))
                        || !Boolean.parseBoolean(runner.getProperty(AgentConfiguration.CHUNK_ADJACENCY_OPTIMIZATION, "true"))) {
                    throw new IllegalArgumentException("Chunk comparison requires both hooks and its own paired workload");
                }
                settings.put(ChunkLifecycleProbe.COMPARE_PROPERTY, "true");
            }
            if (workload.chunkComparison() || Boolean.parseBoolean(runner.getProperty(ChunkLifecycleProbe.PROFILE_PROPERTY))) {
                settings.put(ChunkLifecycleProbe.PROFILE_PROPERTY, "true");
            }
            if (Boolean.parseBoolean(runner.getProperty(VehicleCadenceProbe.COMPARE_PROPERTY))) {
                if (!workload.vehicleStress() || !Boolean.parseBoolean(
                        runner.getProperty(AgentConfiguration.PARKED_VEHICLE_OPTIMIZATION, "true"))) {
                    throw new IllegalArgumentException("The parked cadence comparison requires vehicles72 and the parked vehicle hook");
                }
                settings.put(VehicleCadenceProbe.COMPARE_PROPERTY, "true");
            }
            if (workload.vehicleStress()) {
                if (moving) throw new IllegalArgumentException("vehicles72 measures natural vehicle work without a synthetic spike");
                settings.put(StreamingWorkload.VEHICLE_SEED, Long.toString(workload.vehicleSeed()));
                settings.put(AgentConfiguration.MOVING_OBJECT_TELEMETRY_PROPERTY, "true");
            }
            settings.put("pz.it.streaming", "true");
            if (moving) {
                settings.put("pz.it.movingObjects", "true");
                settings.put(AgentConfiguration.MOVING_OBJECT_TELEMETRY_PROPERTY, "true");
            }
            settings.put(StreamingWorkload.PROFILE, runner.getProperty(StreamingWorkload.PROFILE, "smoke"));
            runner.stringPropertyNames().stream().filter(IntegrationLaunchSettings::isOptimizationSwitch).sorted()
                .forEach(key -> {
                    String value = runner.getProperty(key);
                    if (!value.equals("true") && !value.equals("false")) {
                        throw new IllegalArgumentException("Expected true or false for " + key);
                    }
                    settings.put(key, value);
                });
        }
        ChunkMigrationFixture.configure(runner, settings, streaming, configured);
        // Journal/replay state is owned by this run even when task service settings are reused.
        settings.put(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY, run.resolve("journal").toString());
        return settings;
    }

    static boolean isOptimizationSwitch(String key) {
        return key.matches("apocbr\\.optimization\\.[A-Za-z][A-Za-z0-9]*\\.enabled");
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
