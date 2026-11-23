package cloud.apocalipse.pz.agent.features.http;

import feign.Feign;
import feign.Request;
import feign.Response;
import feign.Retryer;
import feign.http2client.Http2Client;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Creates bounded synchronous Feign clients backed by the JDK HTTP/2 client. */
public final class FeignClientFactory {
    private static final int MAX_RESPONSE_BYTES = 4_096;
    private static final int MAX_CAUSE_DEPTH = 8;

    private FeignClientFactory() {
    }

    public static <T> ManagedClient<T> create(
            Class<T> apiType,
            URI endpoint,
            Duration connectTimeout,
            Duration requestTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_2)
            .build();

        try {
            T api = Feign.builder()
                .client(new Http2Client(httpClient))
                .options(new Request.Options(
                    connectTimeout.toMillis(),
                    TimeUnit.MILLISECONDS,
                    requestTimeout.toMillis(),
                    TimeUnit.MILLISECONDS,
                    false
                ))
                .retryer(Retryer.NEVER_RETRY)
                .errorDecoder((methodKey, response) -> {
                    int statusCode = response.status();
                    response.close();
                    return new HttpStatusException(statusCode);
                })
                .target(apiType, endpoint.resolve("/").toString());
            return new ManagedClient<>(api, httpClient);
        } catch (RuntimeException failure) {
            httpClient.shutdownNow();
            throw failure;
        }
    }

    public static String readBoundedBody(Response response) throws IOException {
        if (response.body() == null) {
            return "";
        }
        byte[] bytes = response.body().asInputStream().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new IOException("HTTP response body exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static boolean restoreInterruptIfInterrupted(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
            Throwable next = cause.getCause();
            if (next == null || next == cause) {
                break;
            }
            cause = next;
        }
        return false;
    }

    public static final class ManagedClient<T> implements AutoCloseable {
        private final T api;
        private final HttpClient httpClient;

        private ManagedClient(T api, HttpClient httpClient) {
            this.api = api;
            this.httpClient = httpClient;
        }

        public T api() {
            return this.api;
        }

        @Override
        public void close() {
            this.httpClient.shutdownNow();
        }
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("HTTP_" + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return this.statusCode;
        }
    }
}
