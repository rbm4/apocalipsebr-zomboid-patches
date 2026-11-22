package cloud.apocalipse.pz.agent.config;

import java.net.URI;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentConfigurationTest {
    @Test
    void xmlCaseHotfixDefaultsToEnabledAndCanBeDisabled() {
        String previous = System.getProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
        try {
            System.clearProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
            assertTrue(AgentConfiguration.optimizationEnabled(
                AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY));
            System.setProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY, "false");
            assertFalse(AgentConfiguration.optimizationEnabled(
                AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY));
        } finally {
            if (previous == null) System.clearProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY);
            else System.setProperty(AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY, previous);
        }
    }
    @Test
    void productionIsDefaultAndLocalChangesOnlyTransportEndpoints() {
        AgentConfiguration production = AgentConfiguration.load("prod");
        AgentConfiguration local = AgentConfiguration.load("local");

        assertEquals(URI.create("https://plataforma.apocalipse.cloud/api/v1/telemetry"),
            production.telemetryEndpoint());
        assertEquals(URI.create("https://plataforma.apocalipse.cloud/api/server/mod-update/restart"),
            production.restartEndpoint());
        assertEquals(URI.create("http://localhost:8080/api/v1/telemetry"), local.telemetryEndpoint());
        assertEquals(URI.create("http://localhost:8080/api/server/mod-update/restart"), local.restartEndpoint());
        assertTrue(production.restartEnabled());
        assertEquals(production.restartEnabled(), local.restartEnabled());
    }

    @Test
    void rejectsUnknownProfiles() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.load("staging"));
    }

    @Test
    void postgresDefaultsToDisabledAndDoesNotRequireCredentials() {
        var settings = AgentConfiguration.postgresSettings(new Properties(), Map.of());
        assertFalse(settings.enabled());
        assertFalse(settings.accountsEnabled());
    }

    @Test
    void npcEngineDefaultsToEnabledAndCanBeDisabledExplicitly() {
        String previous = System.getProperty(AgentConfiguration.NPC_ENABLED_PROPERTY);
        try {
            System.clearProperty(AgentConfiguration.NPC_ENABLED_PROPERTY);
            assertTrue(AgentConfiguration.npcSettings().enabled());
            System.setProperty(AgentConfiguration.NPC_ENABLED_PROPERTY, "false");
            assertFalse(AgentConfiguration.npcSettings().enabled());
        } finally {
            if (previous == null) System.clearProperty(AgentConfiguration.NPC_ENABLED_PROPERTY);
            else System.setProperty(AgentConfiguration.NPC_ENABLED_PROPERTY, previous);
        }
    }

    @Test
    void postgresEnvironmentOverridesUrlAndLoginBudgetsAreClamped() {
        Properties properties = new Properties();
        properties.setProperty(AgentConfiguration.POSTGRES_ENABLED_PROPERTY, "true");
        properties.setProperty(AgentConfiguration.POSTGRES_URL_PROPERTY, "jdbc:postgresql://property/test");
        properties.setProperty(AgentConfiguration.POSTGRES_POOL_SIZE_PROPERTY, "1");
        properties.setProperty(AgentConfiguration.POSTGRES_LOGIN_WORKFLOW_PROPERTY, "1");
        properties.setProperty(AgentConfiguration.POSTGRES_LOGIN_NETWORK_PROPERTY, "99999");
        var settings = AgentConfiguration.postgresSettings(properties,
            Map.of(AgentConfiguration.POSTGRES_URL_ENVIRONMENT, "jdbc:postgresql://environment/test?password=PRIVATE"));
        assertEquals("jdbc:postgresql://environment/test?password=PRIVATE", settings.jdbcUrl());
        assertTrue(settings.accountsEnabled());
        assertEquals(1, settings.poolSize());
        assertEquals(500, settings.loginWorkflowMillis());
        assertEquals(30000, settings.loginNetworkMillis());
        assertFalse(settings.toString().contains("PRIVATE"));
    }

    @Test
    void postgresRejectsInvalidConfigurationWithoutEchoingValues() {
        Properties properties = new Properties();
        properties.setProperty(AgentConfiguration.POSTGRES_ENABLED_PROPERTY, "true");
        properties.setProperty(AgentConfiguration.POSTGRES_URL_PROPERTY, "PRIVATE_INVALID_URL");
        var badUrl = assertThrows(IllegalArgumentException.class,
            () -> AgentConfiguration.postgresSettings(properties, Map.of()));
        assertFalse(badUrl.toString().contains("PRIVATE"));
        properties.setProperty(AgentConfiguration.POSTGRES_URL_PROPERTY, "jdbc:postgresql://localhost/test");
        properties.setProperty(AgentConfiguration.POSTGRES_POOL_SIZE_PROPERTY, "33");
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.postgresSettings(properties, Map.of()));
        properties.setProperty(AgentConfiguration.POSTGRES_POOL_SIZE_PROPERTY, "PRIVATE");
        var badSize = assertThrows(IllegalArgumentException.class,
            () -> AgentConfiguration.postgresSettings(properties, Map.of()));
        assertFalse(badSize.toString().contains("PRIVATE"));
    }
}
