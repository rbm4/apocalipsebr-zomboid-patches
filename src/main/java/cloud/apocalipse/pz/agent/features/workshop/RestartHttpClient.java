package cloud.apocalipse.pz.agent.features.workshop;

import com.google.gson.JsonParser;
import cloud.apocalipse.pz.agent.features.http.FeignClientFactory;
import cloud.apocalipse.pz.agent.features.http.FeignClientFactory.HttpStatusException;
import feign.FeignException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Matches the publishing workflow's restart contract; separate from the Lua API worker. */
@FunctionalInterface
interface RestartHttpClient {
    enum Result { ACCEPTED, ALREADY_IN_PROGRESS }

    Result post(String token) throws Exception;

    static RestartHttpClient create(URI endpoint) {
        return token -> {
            try (FeignClientFactory.ManagedClient<RestartApi> client = FeignClientFactory.create(
                    RestartApi.class,
                    endpoint,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30))) {
                try (feign.Response response = client.api().post(
                        token,
                        "{\"message\":\"Restart para update de mods\"}")) {
                    return decode(response.status(), FeignClientFactory.readBoundedBody(response));
                }
            } catch (HttpStatusException failure) {
                throw failure;
            } catch (FeignException failure) {
                if (FeignClientFactory.restoreInterruptIfInterrupted(failure)) {
                    throw new InterruptedException("Restart request interrupted");
                }
                throw new IOException("Restart request failed", failure);
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
