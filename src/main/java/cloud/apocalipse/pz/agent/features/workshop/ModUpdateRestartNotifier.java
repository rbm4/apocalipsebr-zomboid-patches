package cloud.apocalipse.pz.agent.features.workshop;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Detached HTTP work only: no game objects or Lua state cross into the worker. */
public final class ModUpdateRestartNotifier {
    private static volatile ModUpdateRestartNotifier instance;
    private static final long MIN_INTERVAL_NANOS = Duration.ofSeconds(60).toNanos();
    private final RestartHttpClient transport;
    private final String token;
    private final LongSupplier clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private boolean attempted;
    private long lastAttempt;
    private boolean acknowledged;

    ModUpdateRestartNotifier(String token, RestartHttpClient transport, LongSupplier clock) {
        this.token = token;
        this.transport = transport;
        this.clock = clock;
    }

    public static void initialize() {
        String enabledOverride = AgentConfiguration.systemProperty(AgentConfiguration.RESTART_ENABLED_PROPERTY);
        if (enabledOverride != null && !Boolean.parseBoolean(enabledOverride)) {
            System.out.println("[ApocBR-Agent] Mod-update restart notifier disabled.");
            return;
        }
        AgentConfiguration configuration = AgentConfiguration.load();
        if (enabledOverride == null && !configuration.restartEnabled()) {
            System.out.println("[ApocBR-Agent] Mod-update restart notifier disabled.");
            return;
        }
        String token = AgentConfiguration.systemProperty(AgentConfiguration.RESTART_API_KEY_PROPERTY);
        if (token == null) token = AgentConfiguration.environmentVariable(
            AgentConfiguration.RESTART_API_KEY_ENVIRONMENT
        );
        if (token == null) token = AgentConfiguration.systemProperty(AgentConfiguration.BRIDGE_SERVER_KEY_PROPERTY);
        if (token == null) {
            System.err.println("[ApocBR-Agent] Mod-update restart notifier disabled: configure restart URL and API key.");
            return;
        }
        try {
            String configuredUrl = AgentConfiguration.environmentVariable(AgentConfiguration.RESTART_URL_ENVIRONMENT);
            String url = AgentConfiguration.systemProperty(AgentConfiguration.RESTART_URL_PROPERTY, configuredUrl);
            URI endpoint = validatedEndpoint(
                url == null || url.isBlank() ? configuration.restartEndpoint().toString() : url,
                token
            );
            instance = new ModUpdateRestartNotifier(
                token,
                RestartHttpClient.create(endpoint),
                System::nanoTime
            );
            System.out.println("[ApocBR-Agent] Mod-update restart notifier enabled.");
        } catch (IllegalArgumentException failure) {
            System.err.println("[ApocBR-Agent] Mod-update restart notifier disabled: configure restart URL and API key.");
        }
    }

    static URI validatedEndpoint(String url, String token) {
        if (url == null || token == null || token.isBlank() || token.length() > 4096
            || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Invalid restart configuration");
        }
        URI endpoint = URI.create(url);
        String host = endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
            || "::1".equals(host) || "[::1]".equals(host);
        if (host == null || !("https".equalsIgnoreCase(endpoint.getScheme())
                || ("http".equalsIgnoreCase(endpoint.getScheme()) && loopback))
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
            || !"/api/server/mod-update/restart".equals(endpoint.getRawPath())) {
            throw new IllegalArgumentException("Invalid restart configuration");
        }
        return endpoint;
    }

    public static void onUpdateDetected() {
        ModUpdateRestartNotifier current = instance;
        if (current != null) current.submit();
    }

    boolean submit() {
        if (!inFlight.compareAndSet(false, true)) return false;
        long now = clock.getAsLong();
        if (acknowledged || (attempted && now - lastAttempt < MIN_INTERVAL_NANOS)) {
            inFlight.set(false);
            return false;
        }
        attempted = true;
        lastAttempt = now;
        try {
            Thread.ofVirtual().name("ApocBR-ModUpdateRestart").inheritInheritableThreadLocals(false)
                .start(this::send);
            return true;
        } catch (RuntimeException failure) {
            inFlight.set(false);
            System.err.println("[ApocBR-Agent] Unable to start restart notification worker.");
            return false;
        }
    }

    private void send() {
        try {
            RestartHttpClient.Result result = transport.post(token);
            acknowledged = true;
            System.out.println(result == RestartHttpClient.Result.ACCEPTED
                ? "[ApocBR-Agent] Mod-update restart requested."
                : "[ApocBR-Agent] Mod-update restart already in progress.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("[ApocBR-Agent] Mod-update restart notification interrupted.");
        } catch (Exception failure) {
            System.err.println("[ApocBR-Agent] Mod-update restart notification failed; next detection may retry after 60 seconds.");
        } finally {
            inFlight.set(false);
        }
    }
}
