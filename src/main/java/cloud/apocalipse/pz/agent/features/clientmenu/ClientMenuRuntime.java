package cloud.apocalipse.pz.agent.features.clientmenu;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import zombie.Lua.LuaManager;
import zombie.core.znet.SteamUser;
import zombie.core.znet.SteamUtils;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Client-owned Lua bindings and Steam identity snapshot. Worker threads never access Lua or Steam. */
public final class ClientMenuRuntime {
    private static final int MAX_IN_FLIGHT = 8;
    private static final int MAX_COMPLETED = 32;
    private static final int MAX_TICKET_RESULTS = 8;
    private static final int MAX_BODY_CHARS = 32_768;
    private static final long MAX_RESPONSE_BYTES = 65_536;
    private static volatile ClientMenuRuntime instance;

    public static boolean skipPhotosensitivityWarning() {
        return AgentConfiguration.clientMenuEnabled() && AgentConfiguration.clientMenuSkipPhotosensitivityWarning()
            && !GameServer.server;
    }

    /** Remove the initial logo entry before vanilla selects and enters its first state. */
    public static void skipStartupLogos(Object machine) {
        if (machine == null || GameServer.server || !AgentConfiguration.clientMenuEnabled()
                || !AgentConfiguration.clientMenuSkipLogos()) return;
        try {
            ClassLoader loader = machine.getClass().getClassLoader();
            Class<?> gameWindow = Class.forName("zombie.GameWindow", false, loader);
            if (gameWindow.getField("states").get(null) != machine) return;
            if (!machine.getClass().getField("firstrun").getBoolean(machine)
                    || machine.getClass().getField("stateIndex").getInt(machine) != 0) return;
            @SuppressWarnings("unchecked")
            java.util.List<Object> states = (java.util.List<Object>) machine.getClass().getField("states").get(machine);
            if (states.isEmpty() || !"zombie.gameStates.TISLogoState".equals(states.get(0).getClass().getName())) return;
            states.remove(0);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Startup must proceed normally if this build changes its state-machine shape.
        }
    }

    private final Thread luaOwner = Thread.currentThread();
    private final KahluaTable environment;
    private final ClientApiTransport transport;
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Result> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SteamWebApiTicketProbe.TicketResult> ticketResults = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> completedOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> ticketResultOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean steamReady;
    private volatile String steamId;
    private volatile boolean closed;

    private ClientMenuRuntime(KahluaTable environment, ClientApiTransport transport,
            boolean steamReady, String steamId) {
        this.environment = environment;
        this.transport = transport;
        this.steamReady = steamReady;
        this.steamId = steamId;
    }

    public static synchronized void steamInitialized() {
        if (GameServer.server) return;
        boolean ready = false;
        String id = null;
        try {
            ready = SteamUtils.isSteamModeEnabled();
            if (ready) {
                id = SteamUser.GetSteamIDString();
                ready = id != null && !id.isBlank() && !"0".equals(id);
            }
        } catch (RuntimeException | LinkageError failure) {
            ready = false;
        }
        ClientMenuRuntime current = instance;
        if (current != null) {
            current.steamReady = ready;
            current.steamId = id;
        }
        if (ready) SteamWebApiTicketProbe.start();
        else SteamWebApiTicketProbe.steamUnavailable();
    }

    /** Called after vanilla LuaManager.init(), before Lua scripts and the main-menu event load. */
    public static synchronized void luaInitialized() {
        if (GameServer.server || !AgentConfiguration.clientMenuEnabled() || LuaManager.env == null) return;
        ClientApiTransport transport = null;
        try { transport = ClientApiTransport.configured(); }
        catch (RuntimeException invalidConfiguration) {
            DebugLog.log("[ApocBR][ClientMenu] API endpoint not configured; Lua API exposed without transport");
        }
        ClientMenuRuntime previous = instance;
        if (previous != null && previous.environment == LuaManager.env) {
            if (transport != null) transport.close();
            return;
        }
        if (previous != null) previous.close();
        ClientMenuRuntime bridge = new ClientMenuRuntime(LuaManager.env, transport,
            SteamUtils.isSteamModeEnabled(), safeSteamId());
        bridge.bind();
        instance = bridge;
    }

    private static String safeSteamId() {
        try {
            return SteamUtils.isSteamModeEnabled() ? SteamUser.GetSteamIDString() : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private void bind() {
        KahluaTable api = LuaManager.platform.newTable();
        bind(api, "status", 0, frame -> status());
        bind(api, "request", 3, frame -> request(text(frame, 0), text(frame, 1), nullableText(frame, 2)));
        bind(api, "poll", 1, frame -> poll(text(frame, 0)));
        bind(api, "requestSteamTicket", 1, frame -> requestSteamTicket(text(frame, 0)));
        bind(api, "pollSteamTicket", 1, frame -> pollSteamTicket(text(frame, 0)));
        this.environment.rawset("ApocBRClientApi", api);
    }

    private interface Call { Object run(LuaCallFrame frame); }

    private void bind(KahluaTable api, String name, int argumentCount, Call call) {
        api.rawset(name, (JavaFunction) (frame, count) -> {
            requireOwner();
            if (count != argumentCount) throw new IllegalArgumentException("Invalid ApocBRClientApi arguments");
            frame.push(call.run(frame));
            return 1;
        });
    }

    private Object status() {
        KahluaTable value = LuaManager.platform.newTable();
        value.rawset("ready", !this.closed);
        value.rawset("steamReady", this.steamReady);
        value.rawset("steamId", this.steamId);
        value.rawset("apiConfigured", this.transport != null);
        value.rawset("pending", (double) this.pending.get());
        value.rawset("steamTicketProbe", SteamWebApiTicketProbe.status());
        value.rawset("steamTicketRequest", SteamWebApiTicketProbe.lastRequestDiagnostic());
        return value;
    }

    private Object request(String method, String path, String body) {
        if (this.closed) return null;
        if (method == null || path == null) return null;
        String normalizedMethod = method.toUpperCase(java.util.Locale.ROOT);
        if (!normalizedMethod.equals("GET") && !normalizedMethod.equals("POST")) return null;
        if (body != null && body.length() > MAX_BODY_CHARS) return null;
        if (normalizedMethod.equals("GET") && body != null && !body.isEmpty()) return null;
        String requestId = UUID.randomUUID().toString();
        if (this.transport == null) {
            storeResult(requestId, new Result(false, 0, "API_NOT_CONFIGURED"));
            return requestId;
        }
        if (this.pending.incrementAndGet() > MAX_IN_FLIGHT) {
            this.pending.decrementAndGet();
            return null;
        }
        this.inFlight.put(requestId, Boolean.TRUE);
        String requestBody = body == null ? "" : body;
        try {
            Thread.ofVirtual().name("ApocBR-Client-API").inheritInheritableThreadLocals(false)
                .start(() -> execute(requestId, normalizedMethod, path, requestBody));
        } catch (RuntimeException failure) {
            this.inFlight.remove(requestId);
            this.pending.decrementAndGet();
            storeResult(requestId, new Result(false, 0, "WORKER_START_FAILED"));
        }
        return requestId;
    }

    private void execute(String requestId, String method, String path, String body) {
        Result result;
        try {
            result = this.transport.request(method, path, body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = new Result(false, 0, "INTERRUPTED");
        } catch (IOException | RuntimeException failure) {
            result = new Result(false, 0, "TRANSPORT_ERROR");
        } finally {
            this.inFlight.remove(requestId);
            this.pending.decrementAndGet();
        }
        if (!this.closed) storeResult(requestId, result);
    }

    private void storeResult(String requestId, Result result) {
        this.results.put(requestId, result);
        this.completedOrder.add(requestId);
        while (this.results.size() > MAX_COMPLETED) {
            String oldest = this.completedOrder.poll();
            if (oldest == null) break;
            this.results.remove(oldest);
        }
    }

    private Object poll(String requestId) {
        Result result = this.results.remove(requestId);
        if (result == null) return null;
        KahluaTable value = LuaManager.platform.newTable();
        value.rawset("ok", result.ok());
        value.rawset("status", (double) result.status());
        value.rawset("body", result.body());
        return value;
    }

    private Object requestSteamTicket(String identity) {
        if (this.closed || !this.steamReady || identity.isBlank()) return null;
        String requestId = UUID.randomUUID().toString();
        boolean accepted = SteamWebApiTicketProbe.requestTicket(identity,
            result -> storeTicketResult(requestId, result));
        return accepted ? requestId : null;
    }

    private void storeTicketResult(String requestId, SteamWebApiTicketProbe.TicketResult result) {
        if (this.closed) return;
        this.ticketResults.put(requestId, result);
        this.ticketResultOrder.add(requestId);
        while (this.ticketResults.size() > MAX_TICKET_RESULTS) {
            String oldest = this.ticketResultOrder.poll();
            if (oldest == null) break;
            this.ticketResults.remove(oldest);
        }
    }

    private Object pollSteamTicket(String requestId) {
        SteamWebApiTicketProbe.TicketResult result = this.ticketResults.remove(requestId);
        if (result == null) return null;
        this.ticketResultOrder.remove(requestId);
        KahluaTable value = LuaManager.platform.newTable();
        value.rawset("ok", result.successful());
        value.rawset("steamResult", (double) result.steamResult());
        value.rawset("ticket", result.successful()
            ? java.util.Base64.getEncoder().encodeToString(result.ticket()) : null);
        return value;
    }

    private void requireOwner() {
        if (this.closed || Thread.currentThread() != this.luaOwner || LuaManager.env != this.environment)
            throw new IllegalStateException("ApocBRClientApi must be called on the active client Lua thread");
    }

    private static String text(LuaCallFrame frame, int index) {
        Object value = frame.get(index);
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string argument");
        return text;
    }

    private static String nullableText(LuaCallFrame frame, int index) {
        Object value = frame.get(index);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected string argument");
        return text;
    }

    public static synchronized void shutdown() {
        SteamWebApiTicketProbe.shutdown();
        ClientMenuRuntime bridge = instance;
        instance = null;
        if (bridge != null) bridge.close();
    }

    private void close() {
        if (this.closed) return;
        this.closed = true;
        if (this.transport != null) this.transport.close();
        this.inFlight.clear();
        this.results.clear();
        this.ticketResults.clear();
        this.completedOrder.clear();
        this.ticketResultOrder.clear();
    }

    private record Result(boolean ok, int status, String body) {}

    private static final class ClientApiTransport implements AutoCloseable {
        private final URI base;
        private final java.net.http.HttpClient client;
        private volatile boolean closed;

        private ClientApiTransport(URI base) {
            this.base = base;
            this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .version(java.net.http.HttpClient.Version.HTTP_2)
                .build();
        }

        static ClientApiTransport configured() {
            String configured = AgentConfiguration.clientMenuApiBaseUrl();
            if (configured == null || configured.isBlank())
                throw new IllegalArgumentException("Missing client API base URL");
            URI endpoint = URI.create(configured);
            String host = endpoint.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
            String endpointPath = endpoint.getRawPath();
            boolean supportedPath = endpointPath == null || endpointPath.isEmpty() || "/".equals(endpointPath)
                || "/api/v1/client-menu/auth".equals(endpointPath);
            if (host == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null || !supportedPath
                    || !("https".equalsIgnoreCase(endpoint.getScheme())
                        || ("http".equalsIgnoreCase(endpoint.getScheme()) && loopback)))
                throw new IllegalArgumentException(
                    "Client API URL must be HTTPS and contain only an origin or the client-menu auth endpoint");

            // The transport receives individual /api/v1/... paths, so normalize the configured
            // endpoint back to its origin before resolving those paths.
            URI base = endpoint.resolve("/");
            return new ClientApiTransport(base);
        }

        Result request(String method, String path, String body) throws IOException, InterruptedException {
            if (this.closed) return new Result(false, 0, "CLIENT_CLOSED");
            if (!path.matches("/api/v1/[A-Za-z0-9_./?=&%-]{1,512}")
                    || path.contains("..") || path.contains("//") || path.contains("#"))
                return new Result(false, 0, "INVALID_PATH");
            URI uri = this.base.resolve(path);
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (method.equals("POST")) {
                builder.header("Content-Type", "application/json; charset=utf-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.GET();
            }
            java.net.http.HttpResponse<java.io.InputStream> response = this.client.send(builder.build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream input = response.body()) {
                byte[] payload = input.readNBytes((int) MAX_RESPONSE_BYTES + 1);
                if (payload.length > MAX_RESPONSE_BYTES) return new Result(false, response.statusCode(), "RESPONSE_TOO_LARGE");
                int status = response.statusCode();
                return new Result(status >= 200 && status < 300, status,
                    new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        @Override public void close() {
            this.closed = true;
            this.client.shutdownNow();
        }
    }
}
