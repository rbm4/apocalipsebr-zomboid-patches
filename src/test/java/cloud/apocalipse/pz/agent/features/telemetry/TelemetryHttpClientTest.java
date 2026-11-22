package cloud.apocalipse.pz.agent.features.telemetry;

import com.sun.net.httpserver.HttpServer;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryHttpClientTest {
    @Test
    void defaultsToManagerEndpointAndBridgeCredential() {
        withProfile("prod", () -> {
            var configuration = TelemetryHttpClient.Configuration.fromSettings(null, null, "bridge-api-key");

            assertEquals(
                URI.create("https://plataforma.apocalipse.cloud/api/v1/telemetry"),
                configuration.endpoint()
            );
            assertEquals("bridge-api-key", configuration.apiKey());

            var override = TelemetryHttpClient.Configuration.fromSettings(
                "https://manager.example/api/v1/telemetry",
                "telemetry-api-key",
                "bridge-api-key"
            );
            assertEquals("telemetry-api-key", override.apiKey());
        });
    }

    @Test
    void explicitIntegrationEndpointOverridesLocalProfile() {
        withProfile("local", () -> {
            assertEquals(URI.create("http://localhost:8080/api/v1/telemetry"),
                TelemetryHttpClient.Configuration.fromSettings(null, null, "test-key").endpoint());
            assertEquals(URI.create("https://plataforma.apocalipse.cloud/api/v1/telemetry"),
                TelemetryHttpClient.Configuration.fromSettings(
                    "https://plataforma.apocalipse.cloud/api/v1/telemetry", null, "test-key").endpoint());
        });
    }

    private static void withProfile(String profile, Runnable assertions) {
        String previous = System.getProperty(AgentConfiguration.PROFILE_PROPERTY);
        try {
            System.setProperty(AgentConfiguration.PROFILE_PROPERTY, profile);
            assertions.run();
        } finally {
            if (previous == null) System.clearProperty(AgentConfiguration.PROFILE_PROPERTY);
            else System.setProperty(AgentConfiguration.PROFILE_PROPERTY, previous);
        }
    }

    @Test
    void postsOneAuthenticatedSnapshotAndValidatesReceipt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/api/v1/telemetry", exchange -> {
            request.set(exchange.getRequestMethod() + "|"
                + exchange.getRequestHeaders().getFirst("X-API-Key") + "|"
                + exchange.getRequestHeaders().getFirst("Content-Type") + "|"
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"snapshotId\":42,\"serverId\":2,\"serverKey\":\"test-server\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try (TelemetryHttpClient client = new TelemetryHttpClient(
                new TelemetryHttpClient.Configuration(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/telemetry"),
                    "test-api-key",
                    java.time.Duration.ofSeconds(5),
                    java.time.Duration.ofSeconds(10)))) {
            String snapshot = TelemetrySnapshotPayload.encode(
                "42.22.2",
                30_000L,
                1L,
                1_795_000_000_000L,
                1,
                0L,
                new TelemetryRegistry.TickSnapshot(0, 0L, 0L, 0L),
                new TelemetryRegistry.LoopSnapshot(0, 0L),
                null,
                java.util.Map.of(),
                OptimizationTelemetry.Snapshot.EMPTY
            );
            CompletableFuture<Void> delivered = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    client.post(snapshot);
                    delivered.complete(null);
                } catch (Exception failure) {
                    delivered.completeExceptionally(failure);
                }
            });
            assertTrue(worker.isVirtual());
            delivered.get(5, TimeUnit.SECONDS);
            assertNotNull(request.get());
            assertTrue(request.get().startsWith("POST|test-api-key|application/json; charset=utf-8|"));
            assertTrue(request.get().contains("\"schemaVersion\":3"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configurationRejectsInsecureRemoteAndWrongRoutes() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHttpClient.Configuration(
            URI.create("http://telemetry.example/api/v1/telemetry"),
            "test-api-key",
            java.time.Duration.ofSeconds(5),
            java.time.Duration.ofSeconds(10)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHttpClient.Configuration(
            URI.create("https://telemetry.example/other"),
            "test-api-key",
            java.time.Duration.ofSeconds(5),
            java.time.Duration.ofSeconds(10)
        ));
    }
}
