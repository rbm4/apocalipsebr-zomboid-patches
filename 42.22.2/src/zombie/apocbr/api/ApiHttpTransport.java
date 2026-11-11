package zombie.apocbr.api;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Package-private transport: Lua cannot choose URLs, headers, credentials or timeouts. */
final class ApiHttpTransport implements ApiDispatcher.Transport {
    private final HttpClient client;
    private final String apiKey;
    ApiHttpTransport(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4096
                || apiKey.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("MISSING_OR_INVALID_API_KEY");
        this.apiKey = apiKey;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public ApiDispatcher.Reply send(URI uri) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("X-API-Key", apiKey).header("Accept", "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = future.get(10, TimeUnit.SECONDS);
            int status = response.statusCode();
            // Error bodies may contain internal details; never pass them to Lua or logs.
            if (status < 200 || status >= 300)
                return new ApiDispatcher.Reply(status, null, "HTTP_" + status,
                        status == 429 ? retryAfter(response) : 0);
            return new ApiDispatcher.Reply(status, new String(response.body(), StandardCharsets.UTF_8), null, 0);
        } catch (TimeoutException timeout) {
            return new ApiDispatcher.Reply(0, null, "TIMEOUT", 0);
        } catch (ExecutionException failure) {
            String error = failure.getCause() instanceof java.net.http.HttpTimeoutException ? "TIMEOUT"
                    : failure.getCause() instanceof ResponseTooLarge ? "RESPONSE_TOO_LARGE" : "TRANSPORT_ERROR";
            return new ApiDispatcher.Reply(0, null, error, 0);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static long retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try { return Math.max(60_000, Math.min(3600, Long.parseLong(value)) * 1000); }
        catch (NumberFormatException ignored) {
            try {
                return Math.max(60_000, Math.min(3_600_000,
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                                - System.currentTimeMillis()));
            } catch (RuntimeException invalid) { return 60_000; }
        }
    }

    @Override public void close() { client.shutdownNow(); }

    private static final class ResponseTooLarge extends RuntimeException {}

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 16_384 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
