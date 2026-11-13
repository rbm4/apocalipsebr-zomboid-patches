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
        CHARACTER_BALANCE,
        CHARACTER_LOGIN,
        CHARACTER_DEBIT,
        CHARACTER_CREDIT;

        String method() { return this==CHARACTER_BALANCE?"GET":"POST"; }

        // Package-private: never exposed as a Lua method.
        URI resolve(String parameters) {
            if (parameters == null || parameters.length() > 1024)
                throw new IllegalArgumentException("INVALID_PARAMETERS");
            JSONTokener parser = new JSONTokener(parameters);
            if (!(parser.nextValue() instanceof JSONObject json) || parser.nextClean() != 0
                    || json.length() != (mutation()?6:3)) throw new IllegalArgumentException("INVALID_PARAMETERS");
            Object runtime = json.get("runtimeUuid"), steam = json.get("steamId"), server = json.get("sourceServerId");
            if (!(runtime instanceof String uuid) || uuid.length() != 36
                    || !UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)
                    || !(steam instanceof String steamId) || !steamId.matches("[0-9]{17}")
                    || !(server instanceof Number number)) throw new IllegalArgumentException("INVALID_PARAMETERS");
            long serverId = new java.math.BigDecimal(number.toString()).longValueExact();
            if (serverId < 1 || serverId > 9_007_199_254_740_991L)
                throw new IllegalArgumentException("INVALID_PARAMETERS");
            if (mutation()) debitBody(json);
            return URI.create("https://plataforma.apocalipse.cloud/api/v1/game/characters/"
                    + UUID.fromString(uuid) + (this==CHARACTER_CREDIT?"/credit":this==CHARACTER_DEBIT?"/debit":this==CHARACTER_LOGIN?"/bind":"/balance")
                    + "?sourceServerId=" + serverId + "&steamId=" + steamId);
        }

        private boolean mutation() { return this==CHARACTER_DEBIT || this==CHARACTER_CREDIT; }

        private static String debitBody(JSONObject json) {
            if (!(json.get("amount") instanceof Number number)
                    || !(json.get("reason") instanceof String reason) || !reason.matches("[A-Za-z0-9 _.:%-]{1,128}")
                    || !(json.get("idempotencyKey") instanceof String key) || !key.matches("[A-Za-z0-9_.:%-]{1,160}"))
                throw new IllegalArgumentException("INVALID_PARAMETERS");
            long amount = new java.math.BigDecimal(number.toString()).longValueExact();
            if (amount<1 || amount>1_000_000_000) throw new IllegalArgumentException("INVALID_PARAMETERS");
            return new JSONObject().put("amount",amount).put("reason",reason).put("idempotencyKey",key).toString();
        }

        String body(String parameters) {
            return mutation()?debitBody(new JSONObject(parameters)):null;
        }

        Map<String, Object> decode(String body) {
            JSONTokener parser = new JSONTokener(body);
            if (!(parser.nextValue() instanceof JSONObject json) || parser.nextClean() != 0
                    || !(json.get("balance") instanceof Number number))
                throw new IllegalArgumentException("INVALID_RESPONSE");
            double balance = number.doubleValue();
            if (!Double.isFinite(balance) || Math.abs(balance) > 9_007_199_254_740_991d)
                throw new IllegalArgumentException("INVALID_RESPONSE");
            if (mutation()) {
                if (!(json.get("transactionId") instanceof String transaction) || !transaction.matches("[A-Za-z0-9_.:%-]{1,160}")
                        || !(json.get("idempotencyKey") instanceof String key) || !key.matches("[A-Za-z0-9_.:%-]{1,160}"))
                    throw new IllegalArgumentException("INVALID_RESPONSE");
                return Map.of("balance",balance,"transactionId",transaction,"idempotencyKey",key);
            }
            return Map.of("balance", balance);
        }
    }

    // Stable Lua access without depending on nested-class table naming.
    public static final Endpoint CHARACTER_BALANCE = Endpoint.CHARACTER_BALANCE;
    public static final Endpoint CHARACTER_LOGIN = Endpoint.CHARACTER_LOGIN;
    public static final Endpoint CHARACTER_DEBIT = Endpoint.CHARACTER_DEBIT;
    public static final Endpoint CHARACTER_CREDIT = Endpoint.CHARACTER_CREDIT;

    /** Server-thread-only native save blobs for market custody; never accepted from client packets. */
    public static String encodeItem(zombie.inventory.InventoryItem item) {
        if (!ApiBridgeLifecycle.isOwnerThread()) return null;
        return CourierItemCodec.encode(item);
    }

    public static zombie.inventory.InventoryItem decodeItem(String blob) {
        if (!ApiBridgeLifecycle.isOwnerThread()) return null;
        return CourierItemCodec.decode(blob);
    }

    /** Returns an opaque ID when accepted, or nil; rejected calls never invoke callback. */
    public static String request(Endpoint endpoint, String parametersJson, LuaClosure callback) {
        return ApiBridgeLifecycle.request(endpoint, parametersJson, callback);
    }

    public static boolean isEnabled() { return ApiBridgeLifecycle.isEnabled(); }

    /** Last synchronous rejection on the server thread; read immediately after a nil ID. */
    public static String getLastRejection() { return ApiBridgeLifecycle.lastRejection(); }
}
