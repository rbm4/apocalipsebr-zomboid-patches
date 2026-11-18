package cloud.apocalipse.pz.agent.features.telemetry;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryHttpClientTest {
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
        try (TelemetryHttpClient client = new TelemetryHttpClient(new TelemetryHttpClient.Configuration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/telemetry"),
                "test-api-key", Duration.ofSeconds(1), Duration.ofSeconds(2)))) {
            String snapshot = TelemetrySnapshotPayload.encode(
                "42.22.2", 30_000L, 1L, 1_795_000_000_000L, 1, 0L, java.util.Map.of()
            );
            client.post(snapshot);
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
            "test-api-key", Duration.ofSeconds(1), Duration.ofSeconds(2)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryHttpClient.Configuration(
            URI.create("https://telemetry.example/other"),
            "test-api-key", Duration.ofSeconds(1), Duration.ofSeconds(2)
        ));
    }
}
