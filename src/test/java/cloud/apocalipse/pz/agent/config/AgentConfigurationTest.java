package cloud.apocalipse.pz.agent.config;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentConfigurationTest {
    @Test
    void productionIsTheDefaultAndLocalOverridesOnlyEndpoints() {
        String originalProfile = System.getProperty("apocbr.profile");
        try {
            System.clearProperty("apocbr.profile");
            AgentConfiguration defaults = AgentConfiguration.load();
            assertEquals("prod", defaults.profile());
            assertEquals(
                URI.create("https://plataforma.apocalipse.cloud/api/v1/telemetry"),
                defaults.telemetryEndpoint()
            );
            assertEquals(
                URI.create("https://plataforma.apocalipse.cloud/api/server/mod-update/restart"),
                defaults.restartEndpoint()
            );
            assertTrue(defaults.restartEnabled());

            AgentConfiguration local = AgentConfiguration.load("local");
            assertEquals("local", local.profile());
            assertEquals(URI.create("http://localhost:8080/api/v1/telemetry"), local.telemetryEndpoint());
            assertEquals(
                URI.create("http://localhost:8080/api/server/mod-update/restart"),
                local.restartEndpoint()
            );
            assertTrue(local.restartEnabled());

            System.setProperty("apocbr.profile", "local");
            assertEquals("local", AgentConfiguration.load().profile());
        } finally {
            if (originalProfile == null) {
                System.clearProperty("apocbr.profile");
            } else {
                System.setProperty("apocbr.profile", originalProfile);
            }
        }
    }

    @Test
    void rejectsUnknownProfile() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfiguration.load("staging"));
    }
}
