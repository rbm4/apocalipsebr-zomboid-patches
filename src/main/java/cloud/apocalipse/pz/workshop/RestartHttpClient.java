package cloud.apocalipse.pz.workshop;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Matches the publishing workflow's restart contract; separate from the Lua API worker. */
@FunctionalInterface
interface RestartHttpClient {
    enum Result { ACCEPTED, ALREADY_IN_PROGRESS }

    Result post(URI endpoint, String token) throws Exception;

    static RestartHttpClient create() {
        return (endpoint, token) -> {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-API-Key", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"Restart para update de mods\"}"))
                    .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 4096));
                return decode(response.statusCode(), response.body());
            } finally {
                client.shutdownNow();
            }
        };
    }

    static Result decode(int statusCode, String body) throws IOException {
        try {
            var value = JsonParser.parseString(body).getAsJsonObject().get("status");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IOException("Invalid restart response");
            }
            String status = value.getAsString();
            if (statusCode == 202 && "accepted".equals(status)) return Result.ACCEPTED;
            if (statusCode == 200 && "already_in_progress".equals(status)) return Result.ALREADY_IN_PROGRESS;
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid restart response");
        }
        throw new IOException("Unexpected restart response");
    }
}
