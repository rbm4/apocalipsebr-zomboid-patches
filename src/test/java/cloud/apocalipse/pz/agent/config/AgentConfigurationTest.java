package cloud.apocalipse.pz.agent.config;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigurationTest {
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
}
