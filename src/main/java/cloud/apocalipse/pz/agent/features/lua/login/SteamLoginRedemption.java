package cloud.apocalipse.pz.agent.features.lua.login;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Server-only exchange. The game thread supplies immutable connection identity snapshots. */
final class SteamLoginRedemption {
    private static final Gson JSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    private SteamLoginRedemption() { }

    record Result(boolean accepted, String reason) { }

    static Result redeem(String token, long steamId, String username) throws InterruptedException {
        String apiKey = AgentConfiguration.steamLoginApiKey();
        if (apiKey.isBlank()) return new Result(false, "MISSING_API_KEY");
        HttpRequest request;
        try {
            URI uri = URI.create(AgentConfiguration.steamLoginRedeemUrl());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return new Result(false, "INVALID_REDEEM_URL");
            }
            String body = JSON.toJson(new Redemption(token, Long.toUnsignedString(steamId), username));
            request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("X-API-Key", apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        } catch (IllegalArgumentException invalidConfiguration) {
            return new Result(false, "INVALID_REDEEM_CONFIGURATION");
        }
        var response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            int status = response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode();
            return new Result(status == 204, "HTTP_" + status);
        } catch (TimeoutException timeout) {
            return new Result(false, "HTTP_TIMEOUT");
        } catch (ExecutionException failure) {
            return new Result(false, "HTTP_ERROR_" + SteamLoginDiagnostics.failureType(failure));
        } finally {
            if (!response.isDone()) response.cancel(true);
        }
    }

    private record Redemption(String token, String steamId, String username) { }
}
