package zombie.apocbr.api;

import java.net.URI;
import java.util.UUID;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;
import se.krka.kahlua.vm.LuaClosure;

/** Server-Lua entry point. URLs, credentials and HTTP policy stay in Java. */
public final class ApocBrApiBridge {
    private ApocBrApiBridge() {}

    public enum Endpoint {
        CHARACTER_BALANCE;

        // Package-private: never exposed as a Lua method.
        URI resolve(String parameters) {
            if (parameters == null || parameters.length() > 1024)
                throw new IllegalArgumentException("INVALID_PARAMETERS");
            JSONTokener parser = new JSONTokener(parameters);
            if (!(parser.nextValue() instanceof JSONObject json) || parser.nextClean() != 0
                    || json.length() != 3) throw new IllegalArgumentException("INVALID_PARAMETERS");
            Object runtime = json.get("runtimeUuid"), steam = json.get("steamId"), server = json.get("sourceServerId");
            if (!(runtime instanceof String uuid) || uuid.length() != 36
                    || !UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)
                    || !(steam instanceof String steamId) || !steamId.matches("[0-9]{17}")
                    || !(server instanceof Number number)) throw new IllegalArgumentException("INVALID_PARAMETERS");
            long serverId = new java.math.BigDecimal(number.toString()).longValueExact();
            if (serverId < 1 || serverId > 9_007_199_254_740_991L)
                throw new IllegalArgumentException("INVALID_PARAMETERS");
            return URI.create("https://plataforma.apocalipse.cloud/api/v1/game/characters/"
                    + UUID.fromString(uuid) + "/balance?sourceServerId=" + serverId + "&steamId=" + steamId);
        }

        Map<String, Object> decode(String body) {
            JSONTokener parser = new JSONTokener(body);
            if (!(parser.nextValue() instanceof JSONObject json) || parser.nextClean() != 0
                    || !(json.get("balance") instanceof Number number))
                throw new IllegalArgumentException("INVALID_RESPONSE");
            double balance = number.doubleValue();
            if (!Double.isFinite(balance) || Math.abs(balance) > 9_007_199_254_740_991d)
                throw new IllegalArgumentException("INVALID_RESPONSE");
            return Map.of("balance", balance);
        }
    }

    // Stable Lua access without depending on nested-class table naming.
    public static final Endpoint CHARACTER_BALANCE = Endpoint.CHARACTER_BALANCE;

    /** Returns an opaque ID when accepted, or nil; rejected calls never invoke callback. */
    public static String request(Endpoint endpoint, String parametersJson, LuaClosure callback) {
        return ApiBridgeLifecycle.request(endpoint, parametersJson, callback);
    }

    public static boolean isEnabled() { return ApiBridgeLifecycle.isEnabled(); }

    /** Last synchronous rejection on the server thread; read immediately after a nil ID. */
    public static String getLastRejection() { return ApiBridgeLifecycle.lastRejection(); }
}
