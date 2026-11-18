package cloud.apocalipse.pz.agent.features.workshop;

import com.google.gson.JsonParser;
import cloud.apocalipse.pz.agent.features.http.JsonHttpTransport;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Matches the publishing workflow's restart contract; separate from the Lua API worker. */
@FunctionalInterface
interface RestartHttpClient {
    enum Result { ACCEPTED, ALREADY_IN_PROGRESS }

    Result post(URI endpoint, String token) throws Exception;

    static RestartHttpClient create() {
        return (endpoint, token) -> {
            try (JsonHttpTransport transport = new JsonHttpTransport(Duration.ofSeconds(5))) {
                JsonHttpTransport.Response response = transport.post(
                    endpoint,
                    token,
                    "application/json",
                    "{\"message\":\"Restart para update de mods\"}",
                    Duration.ofSeconds(30)
                );
                return decode(response.statusCode(), response.body());
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
