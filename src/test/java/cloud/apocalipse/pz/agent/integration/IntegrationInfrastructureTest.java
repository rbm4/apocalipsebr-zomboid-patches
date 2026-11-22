package cloud.apocalipse.pz.agent.integration;

import cloud.apocalipse.pz.agent.AgentModules;
import cloud.apocalipse.pz.agent.HookCheck;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationInfrastructureTest {
    @Test void jdbcEnvironmentSurvivesActualChildProcessWithQuerySeparators() throws Exception {
        String url = "jdbc:postgresql://localhost/fixture?user=fixture&password=test-only%26value";
        String executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var builder = new ProcessBuilder(executable,
            "-Dpz.it.useTaskConfiguration=true", "-Dapocbr.postgres.enabled=true",
            "-cp", System.getProperty("java.class.path"), IntegrationConfigurationSmokeMain.class.getName(),
            IntegrationConfigurationSmokeMain.sha256(url)).redirectErrorStream(true);
        builder.environment().put("APOCBR_POSTGRES_URL", url);
        for (String key : java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(key);
        }
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), "Configuration child failed");
            assertTrue(output.contains("full JDBC environment reached child JVM intact"));
            assertFalse(output.contains("test-only"));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    @Test void taskConfigurationRequiresOptInAndForwardsSettingsWithoutSharingJournalState() {
        var runner = new java.util.Properties();
        runner.setProperty("apocbr.postgres.enabled", "true");
        runner.setProperty("apocbr.postgres.url", "jdbc:postgresql://localhost/test_fixture");
        runner.setProperty("apocbr.bridge.serverKey", "test-only-key");
        runner.setProperty("apocbr.telemetry.endpoint", "https://plataforma.apocalipse.cloud/api/v1/telemetry");
        runner.setProperty("apocbr.postgres.journalDirectory", "unrelated-journal");
        runner.setProperty("unrelated.setting", "ignored");
        var run = java.nio.file.Path.of("target/fixture");
        var isolated = IntegrationLaunchSettings.properties(runner, run, "http://127.0.0.1:1234");
        assertEquals("false", isolated.get("apocbr.postgres.enabled"));
        assertFalse(isolated.containsKey("apocbr.postgres.url"));
        assertEquals("integration-only", isolated.get("apocbr.telemetry.apiKey"));
        assertEquals("http://127.0.0.1:1234/api/v1/telemetry", isolated.get("apocbr.telemetry.endpoint"));
        runner.setProperty(IntegrationLaunchSettings.TASK_CONFIGURATION, "true");
        var configured = IntegrationLaunchSettings.properties(runner, run, "http://127.0.0.1:1234");
        assertEquals("true", configured.get("apocbr.postgres.enabled"));
        assertEquals(runner.getProperty("apocbr.postgres.url"), configured.get("apocbr.postgres.url"));
        assertEquals("test-only-key", configured.get("apocbr.bridge.serverKey"));
        assertEquals(runner.getProperty("apocbr.telemetry.endpoint"), configured.get("apocbr.telemetry.endpoint"));
        assertFalse(configured.containsKey("apocbr.telemetry.apiKey"), "Use configured credential fallback");
        assertEquals(run.resolve("journal").toString(), configured.get("apocbr.postgres.journalDirectory"));
        assertFalse(configured.containsKey("unrelated.setting"));
    }

    @Test void onlyConfiguredServiceEnvironmentIsForwardedAndJavaInjectionRemainsRemoved() {
        var environment = java.util.Map.of("APOCBR_POSTGRES_URL", "jdbc:postgresql://localhost/fixture",
            "APOCBR_BRIDGE_API_KEY", "test-key", "JAVA_TOOL_OPTIONS", "-javaagent:unrelated.jar",
            "APOCBR_UNRELATED", "ignored", "PATH", "preserved");
        var child = new java.util.HashMap<>(environment);
        var runner = new java.util.Properties();
        IntegrationLaunchSettings.configureEnvironment(child, environment, runner);
        assertEquals(java.util.Map.of("PATH", "preserved"), child);
        runner.setProperty(IntegrationLaunchSettings.TASK_CONFIGURATION, "true");
        IntegrationLaunchSettings.configureEnvironment(child, environment, runner);
        assertEquals(environment.get("APOCBR_POSTGRES_URL"), child.get("APOCBR_POSTGRES_URL"));
        assertEquals("test-key", child.get("APOCBR_BRIDGE_API_KEY"));
        assertFalse(child.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(child.containsKey("APOCBR_UNRELATED"));
    }

    @Test void everyRegisteredModuleHasUnambiguousInstallationChecks() {
        var names = new HashSet<String>();
        for (var module : AgentModules.all()) {
            assertTrue(names.add(module.name()), "Duplicate module name");
            assertFalse(module.hookChecks().isEmpty(), "Missing hook checks for " + module.name());
            var ids = new HashSet<String>();
            for (var check : module.hookChecks()) {
                assertTrue(ids.add(check.id()), "Duplicate hook id in " + module.name());
                assertTrue(cloud.apocalipse.pz.agent.integration.probe.HookProbe
                    .isGameClass(check.targetClass().replace('.', '/')), "Expected original game class");
                assertTrue(check.helperClass().startsWith("cloud.apocalipse.pz.agent."));
            }
        }
        assertThrows(IllegalArgumentException.class,
            () -> new HookCheck("", "zombie.Target", "run", "Helper", "call", false));
    }

    @Test void coloredAndPlainResultsRemainReadableAndColorsAreReset() {
        assertTrue(IntegrationLog.colorEnabled(null, null));
        assertFalse(IntegrationLog.colorEnabled(null, "1"));
        assertTrue(IntegrationLog.colorEnabled("true", "1"));
        assertFalse(IntegrationLog.colorEnabled("false", null));
        assertEquals("\u001b[32m[Server-IT] [PASS] hook\u001b[0m",
            IntegrationLog.format("PASS", "32", "hook", true));
        assertEquals("\u001b[31m[Server-IT] [FAIL] hook\u001b[0m",
            IntegrationLog.format("FAIL", "31", "hook", true));
        assertEquals("[Server-IT] [FAIL] hook", IntegrationLog.format("FAIL", "31", "hook", false));
    }
}
