package cloud.apocalipse.pz.agent.features.lua.radio;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.time.Duration;
import java.util.Locale;
import zombie.GameTime;
import zombie.network.GameServer;

/** Radio-only lifecycle. Other legacy backend observations are outside this port. */
public final class ApocBRBackendBridgeHooks {
    private static volatile ApocBRBackendBridge bridge;
    private static volatile Thread owner;
    private ApocBRBackendBridgeHooks() { }
    public static void initialize() {
        if (!GameServer.server || bridge != null
                || !Boolean.parseBoolean(AgentConfiguration.systemProperty("apocbr.bridge.enabled", "true"))) return;
        ApocBRBackendBridge created = null;
        ApocBRBackendHttpDelivery delivery = null;
        try {
            String key = AgentConfiguration.systemProperty("apocbr.bridge.serverKey", "").strip().toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException();
            created = new ApocBRBackendBridge(key, ApocBRBackendBridge.Settings.defaults());
            if (!Boolean.parseBoolean(AgentConfiguration.systemProperty("apocbr.bridge.captureOnly", "false"))) {
                delivery = new ApocBRBackendHttpDelivery(ApocBRBackendHttpDelivery.Configuration.fromProperties(),
                    code -> System.out.println("[ApocBR-Agent][Radio] " + code));
                created.start(delivery);
            }
            owner = Thread.currentThread();
            bridge = created;
        } catch (RuntimeException failure) {
            if (created != null) created.close();
            if (delivery != null) delivery.close();
            System.err.println("[ApocBR-Agent][Radio] Invalid configuration; radio bridge disabled");
        }
    }
    static ApocBRBackendBridge current() {
        return GameServer.server && Thread.currentThread() == owner ? bridge : null;
    }
    static ApocBRBackendBridge.Context context(long ttlMillis) {
        long now = System.currentTimeMillis();
        GameTime time = GameTime.getInstance();
        String game = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d", time.getYear(), time.getMonth()+1,
            time.getDay()+1, (int)time.getTimeOfDay(), time.getMinutes());
        return new ApocBRBackendBridge.Context(now, game, now + ttlMillis);
    }
    public static void shutdown() {
        var current = bridge;
        bridge = null; owner = null;
        if (current == null) return;
        current.close();
        try {
            if (!current.awaitTermination(Duration.ofSeconds(12)))
                throw new IllegalStateException("Radio worker failed to stop");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping radio worker");
        }
    }
}
