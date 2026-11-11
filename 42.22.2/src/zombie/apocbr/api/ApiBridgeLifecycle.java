package zombie.apocbr.api;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Engine lifecycle adapter. Not registered with the Lua exposer. */
public final class ApiBridgeLifecycle {
    private record Pending(ApocBrApiBridge.Endpoint endpoint, LuaClosure callback) {}
    private static volatile ApiBridgeLifecycle instance;
    private final Thread owner = Thread.currentThread();
    private final ApiDispatcher dispatcher;
    // Main-thread only: Lua closures never enter the dispatcher's detached queues.
    private final Map<String, Pending> callbacks = new HashMap<>();
    private String rejection;

    ApiBridgeLifecycle(ApiDispatcher dispatcher) { this.dispatcher = dispatcher; }

    public static synchronized void initialize() {
        if (!GameServer.server || instance != null
                || !Boolean.parseBoolean(System.getProperty("apocbr.bridge.api.enabled", "true"))) return;
        ApiHttpTransport transport = null;
        try {
            transport = new ApiHttpTransport(System.getProperty("apocbr.bridge.serverKey"));
            ApiDispatcher dispatcher = new ApiDispatcher(transport,
                    Long.getLong("apocbr.bridge.api.requestIntervalMs", ApiDispatcher.MIN_INTERVAL_MS), System::nanoTime);
            ApiBridgeLifecycle bridge = new ApiBridgeLifecycle(dispatcher);
            dispatcher.start();
            instance = bridge;
            DebugLog.log("[ApocBR][API] started");
        } catch (RuntimeException invalid) {
            if (transport != null) transport.close();
            DebugLog.log("[ApocBR][API] invalid configuration; API bridge disabled");
        }
    }

    private static ApiBridgeLifecycle current() {
        ApiBridgeLifecycle bridge = instance;
        return GameServer.server && bridge != null && bridge.owner == Thread.currentThread() ? bridge : null;
    }

    static boolean isEnabled() {
        ApiBridgeLifecycle bridge = current();
        return bridge != null && bridge.dispatcher.isRunning();
    }

    static String lastRejection() {
        ApiBridgeLifecycle bridge = current();
        return bridge == null ? "UNAVAILABLE" : bridge.rejection;
    }

    static String request(ApocBrApiBridge.Endpoint endpoint, String parameters, LuaClosure callback) {
        ApiBridgeLifecycle bridge = current();
        if (bridge == null) return null;
        bridge.rejection = null;
        if (endpoint == null || callback == null) { bridge.rejection = "INVALID_ARGUMENTS"; return null; }
        URI uri;
        try { uri = endpoint.resolve(parameters); }
        catch (RuntimeException invalid) { bridge.rejection = "INVALID_PARAMETERS"; return null; }
        String id = UUID.randomUUID().toString();
        bridge.rejection = bridge.dispatcher.submit(id, uri);
        if (bridge.rejection != null) return null;
        bridge.callbacks.put(id, new Pending(endpoint, callback));
        return id;
    }

    /** Called by the server loop; a fixed callback budget prevents reentrant starvation. */
    public static void update() {
        ApiBridgeLifecycle bridge = current();
        if (bridge == null) return;
        for (int count = 0; count < 8; count++) {
            ApiDispatcher.Result result = bridge.dispatcher.poll();
            if (result == null) break;
            Pending pending = bridge.callbacks.remove(result.id());
            if (pending == null) continue;
            KahluaTable table = LuaManager.platform.newTable();
            String error = result.error();
            Map<String, Object> fields = Map.of();
            if (error == null) {
                try { fields = pending.endpoint().decode(result.body()); }
                catch (RuntimeException invalid) { error = "INVALID_RESPONSE"; }
            }
            table.rawset("requestId", result.id());
            table.rawset("ok", error == null);
            table.rawset("status", (double)result.status());
            table.rawset("error", error);
            fields.forEach(table::rawset);
            try { LuaManager.caller.protectedCallVoid(LuaManager.thread, pending.callback(), new Object[]{table}); }
            catch (RuntimeException failure) { DebugLog.log("[ApocBR][API] callback failed"); }
            // A callback may cause server shutdown. Do not drain cancelled callbacks afterward.
            if (instance != bridge || !bridge.dispatcher.isRunning()) break;
        }
    }

    public static synchronized void shutdown() {
        ApiBridgeLifecycle bridge = current();
        if (bridge == null) return;
        bridge.dispatcher.close();
        bridge.callbacks.clear();
        // Terminal for this JVM lifecycle; no hot restart or callbacks during world teardown.
    }
}
