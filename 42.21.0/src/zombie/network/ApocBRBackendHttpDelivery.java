package zombie.network;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Single-event manager API adapter; used only by the bridge's delivery worker. */
public final class ApocBRBackendHttpDelivery implements ApocBRBackendBridge.Delivery {
    public record Configuration(URI endpoint, String apiKey, Duration connectTimeout,
            Duration requestTimeout, Duration requestInterval) {
        public Configuration {
            Objects.requireNonNull(endpoint);
            String host = endpoint.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            if (host == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !("https".equalsIgnoreCase(endpoint.getScheme())
                        || ("http".equalsIgnoreCase(endpoint.getScheme()) && loopback))) {
                throw new IllegalArgumentException("Bridge endpoint requires HTTPS or loopback HTTP");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4_096
                    || apiKey.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new IllegalArgumentException("Invalid bridge API credential");
            }
            checkDuration(connectTimeout, Duration.ofMillis(1), Duration.ofSeconds(30));
            checkDuration(requestTimeout, Duration.ofMillis(1), Duration.ofSeconds(60));
            checkDuration(requestInterval, Duration.ofMillis(1_250), Duration.ofSeconds(60));
        }

        public static Configuration fromProperties() {
            String endpoint = setting("apocbr.bridge.endpoint", "APOCBR_BRIDGE_ENDPOINT");
            String key = setting("apocbr.bridge.apiKey", "APOCBR_BRIDGE_API_KEY");
            if (endpoint == null) throw new IllegalArgumentException("Missing bridge endpoint");
            return new Configuration(URI.create(endpoint), key,
                    Duration.ofMillis(Long.getLong("apocbr.bridge.connectTimeoutMs", 5_000L)),
                    Duration.ofMillis(Long.getLong("apocbr.bridge.requestTimeoutMs", 10_000L)),
                    Duration.ofMillis(Long.getLong("apocbr.bridge.requestIntervalMs", 1_500L)));
        }

        @Override
        public String toString() { return "BridgeHttpConfiguration[redacted]"; }

        private static String setting(String property, String environment) {
            String value = System.getenv(environment);
            return value == null || value.isBlank() ? System.getProperty(property) : value;
        }

        private static void checkDuration(Duration value, Duration min, Duration max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("Invalid bridge HTTP duration");
            }
        }
    }

    public record Status(long requests, long acknowledged, long rejected, int lastHttpStatus,
            String lastError) {}

    private final Configuration configuration;
    private final HttpClient client;
    private final Consumer<String> diagnostics;
    private final boolean logDelivery;
    private List<ApocBRBackendBridge.Event> pendingBatch;
    private String[] pendingBodies;
    private int cursor;
    private int rejectedInBatch;
    private long nextRequestNanos;
    private long nextDiagnosticNanos;
    private volatile long requests;
    private volatile long acknowledged;
    private volatile long rejected;
    private volatile int lastHttpStatus;
    private volatile String lastError;

    public ApocBRBackendHttpDelivery(Configuration configuration, Consumer<String> diagnostics) {
        this.configuration = Objects.requireNonNull(configuration);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.logDelivery = Boolean.parseBoolean(System.getProperty("apocbr.bridge.logDelivery", "true"));
        this.client = HttpClient.newBuilder().connectTimeout(configuration.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void deliver(List<ApocBRBackendBridge.Event> events) throws Exception {
        if (events != this.pendingBatch) {
            if (this.pendingBatch != null && this.cursor < this.pendingBatch.size()) {
                throw failure("BATCH_CHANGED_DURING_RETRY", true, 0);
            }
            this.pendingBatch = events;
            this.pendingBodies = new String[events.size()];
            this.cursor = 0;
            this.rejectedInBatch = 0;
            progress("batch collected events=" + events.size());
        } else {
            progress("batch resumed remaining=" + (events.size() - this.cursor));
        }
        while (this.cursor < events.size()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String body = this.pendingBodies[this.cursor];
            if (body == null) {
                try {
                    body = encode(events.get(this.cursor));
                    this.pendingBodies[this.cursor] = body;
                    progress("packed event=" + (this.cursor + 1) + "/" + events.size()
                            + " type=" + events.get(this.cursor).type().name()
                            + " bytes=" + body.getBytes(StandardCharsets.UTF_8).length);
                } catch (RuntimeException invalidEvent) {
                    reject("INVALID_EVENT");
                    continue;
                }
            }
            pace();
            HttpRequest request = HttpRequest.newBuilder(this.configuration.endpoint())
                    .timeout(this.configuration.requestTimeout())
                    .header("X-API-Key", this.configuration.apiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            this.requests++;
            long started = System.nanoTime();
            progress("sending request=" + this.requests + " event=" + (this.cursor + 1)
                    + "/" + events.size() + " type=" + events.get(this.cursor).type().name());
            HttpResponse<byte[]> response = send(request);
            int status = response.statusCode();
            this.lastHttpStatus = status;
            progress("response request=" + this.requests + " status=" + status
                    + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (status == 200) {
                try {
                    Object value = new JSONObject(new String(response.body(), StandardCharsets.UTF_8)).opt("id");
                    if (!(value instanceof String id) || !UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                        throw new IllegalArgumentException("Invalid observation acknowledgment");
                    }
                } catch (RuntimeException malformedAcknowledgment) {
                    throw failure("INVALID_ACKNOWLEDGMENT", false, 0);
                }
                this.acknowledged++;
                this.cursor++;
                this.lastError = null;
                progress("acknowledged request=" + this.requests + " total=" + this.acknowledged);
            } else if (status == 401 || status == 403 || status == 404 || status == 405
                    || (status >= 300 && status < 400)) {
                // Credentials/route/redirect errors require configuration correction.
                throw failure("HTTP_" + status, true, 0);
            } else if (status == 429) {
                throw failure("HTTP_429", false, Math.max(60_000L, retryAfterMillis(response)));
            } else if (status == 408 || status == 409 || status == 425 || status >= 500) {
                throw failure("HTTP_" + status, false, retryAfterMillis(response));
            } else if (status >= 400 && status < 500) {
                // Invalid payload/conflicting identity cannot poison the whole FIFO.
                reject("HTTP_" + status);
            } else {
                throw failure("UNEXPECTED_HTTP_STATUS", false, 0);
            }
        }
        progress("batch complete events=" + events.size() + " rejected=" + this.rejectedInBatch);
    }

    @Override
    public int rejectedInBatch() { return this.rejectedInBatch; }

    public Status status() {
        return new Status(this.requests, this.acknowledged, this.rejected, this.lastHttpStatus, this.lastError);
    }

    @Override
    public void close() { this.client.shutdownNow(); }

    private void pace() throws InterruptedException {
        long remaining = this.nextRequestNanos - System.nanoTime();
        while (this.nextRequestNanos != 0 && remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
            remaining = this.nextRequestNanos - System.nanoTime();
        }
        this.nextRequestNanos = System.nanoTime() + this.configuration.requestInterval().toNanos();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = this.client.sendAsync(request,
                ignored -> new LimitedBodySubscriber());
        try {
            // Bound the entire exchange, including slow/stalled response bodies.
            return pending.get(this.configuration.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw failure("REQUEST_TIMEOUT", false, 0);
        } catch (ExecutionException transportError) {
            throw failure(transportError.getCause() instanceof java.net.http.HttpTimeoutException
                    ? "REQUEST_TIMEOUT" : "TRANSPORT_ERROR", false, 0);
        }
    }

    private void reject(String code) {
        progress("event rejected event=" + (this.cursor + 1) + " code=" + code);
        this.rejected++;
        this.rejectedInBatch++;
        this.cursor++;
        report(code, false);
    }

    private ApocBRBackendBridge.DeliveryFailure failure(String code, boolean permanent, long retryAfter) {
        progress("delivery failed code=" + code + " permanent=" + permanent + " retryAfterMs=" + retryAfter);
        report(code, permanent);
        return new ApocBRBackendBridge.DeliveryFailure(code, permanent, retryAfter);
    }

    private void report(String code, boolean permanent) {
        this.lastError = code;
        long now = System.nanoTime();
        if (permanent || this.nextDiagnosticNanos == 0 || now - this.nextDiagnosticNanos >= 0) {
            this.nextDiagnosticNanos = now + TimeUnit.MINUTES.toNanos(1);
            try { this.diagnostics.accept(code); } catch (RuntimeException ignored) {}
        }
    }

    /** Worker-only metadata; never include bodies, credentials, URLs or exception messages. */
    private void progress(String message) {
        if (!this.logDelivery) return;
        try { this.diagnostics.accept(message); } catch (RuntimeException ignored) {}
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try {
            long seconds = Long.parseLong(value.strip());
            return Math.max(0, Math.min(3_600L, seconds)) * 1_000L;
        } catch (RuntimeException notSeconds) {
            try {
                long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(0, Math.min(3_600_000L, date - System.currentTimeMillis()));
            } catch (RuntimeException notDate) {
                return 0;
            }
        }
    }

    private static String encode(ApocBRBackendBridge.Event event) {
        if (event.serverKey().length() > 80
                || !event.serverKey().matches("[a-z0-9_-]+")
                || (event.gameTime() != null && event.gameTime().length() > 80)) {
            throw new IllegalArgumentException("Invalid manager event scope");
        }
        String payload = new JSONObject(event.publicPayload()).toString();
        if (payload.length() > 4_000) throw new IllegalArgumentException("Manager payload limit exceeded");
        return new JSONObject().put("serverKey", event.serverKey())
                .put("source", event.source()).put("sourceEventId", event.sourceEventId())
                .put("type", event.type().name()).put("occurredAt", event.occurredAt())
                .put("gameTime", event.gameTime()).put("expiresAt", event.expiresAt())
                .put("publicPayloadJson", payload).toString();
    }

    /** Keep responses bounded even when a proxy sends an error page/stream. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return this.result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 8_192 - this.bytes.size()) {
                    this.subscription.cancel();
                    this.result.completeExceptionally(new IllegalStateException("Response limit exceeded"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                this.bytes.writeBytes(chunk);
            }
            this.subscription.request(1);
        }
        @Override public void onError(Throwable failure) { this.result.completeExceptionally(failure); }
        @Override public void onComplete() { this.result.complete(this.bytes.toByteArray()); }
    }
}
