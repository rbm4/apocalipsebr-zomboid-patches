package cloud.apocalipse.pz.agent.features.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Shared bounded JSON POST transport for agent integrations. */
public final class JsonHttpTransport implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 4_096;

    public record Response(int statusCode, String body) {
    }

    private final HttpClient client;

    public JsonHttpTransport(Duration connectTimeout) {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Objects.requireNonNull(connectTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public Response post(URI endpoint, String apiKey, String contentType, String body, Duration requestTimeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("X-API-Key", apiKey)
            .header("Content-Type", contentType)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = this.client.send(
            request,
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                MAX_RESPONSE_BYTES
            )
        );
        return new Response(response.statusCode(), response.body());
    }

    @Override
    public void close() {
        this.client.shutdownNow();
    }
}
