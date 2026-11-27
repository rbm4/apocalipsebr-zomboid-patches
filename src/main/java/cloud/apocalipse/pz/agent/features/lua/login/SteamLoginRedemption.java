package cloud.apocalipse.pz.agent.features.lua.login;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Server-only exchange. The game thread supplies immutable connection identity snapshots. */
final class SteamLoginRedemption {
    private static final Gson JSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    private SteamLoginRedemption() { }

    static boolean redeem(String token, long steamId, String username) throws Exception {
        String apiKey = AgentConfiguration.steamLoginApiKey();
        if (apiKey.isBlank()) return false;
        URI uri = URI.create(AgentConfiguration.steamLoginRedeemUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String body = JSON.toJson(new Redemption(token, Long.toUnsignedString(steamId), username));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
            .header("X-API-Key", apiKey).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        try {
            return response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).statusCode() == 204;
        } finally {
            if (!response.isDone()) response.cancel(true);
        }
    }

    private record Redemption(String token, String steamId, String username) { }
}
