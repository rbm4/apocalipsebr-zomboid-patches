package cloud.apocalipse.pz.agent.features.workshop;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the shaded Feign/JDK transport against a local endpoint. */
public class FeignTransportSmokeMain {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/api/server/mod-update/restart", exchange -> {
            request.set(exchange.getRequestMethod() + "|"
                + exchange.getRequestHeaders().getFirst("X-API-Key") + "|"
                + exchange.getRequestHeaders().getFirst("Content-Type") + "|"
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/server/mod-update/restart"
            );
            if (RestartHttpClient.create(endpoint).post("smoke-test-key")
                    != RestartHttpClient.Result.ACCEPTED) {
                    throw new AssertionError("Unexpected Feign response");
            }
            String expected = "POST|smoke-test-key|application/json|"
                + "{\"message\":\"Restart para update de mods\"}";
            if (!expected.equals(request.get())) {
                throw new AssertionError("Unexpected Feign request: " + request.get());
            }
            System.out.println("Shaded standalone Feign transport smoke check passed.");
        } finally {
            server.stop(0);
        }
    }
}
