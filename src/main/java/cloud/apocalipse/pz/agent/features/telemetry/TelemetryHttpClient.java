package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Authenticated transport for the manager's single-snapshot telemetry endpoint. */
final class TelemetryHttpClient implements AutoCloseable {
    record Configuration(URI endpoint, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        Configuration {
            Objects.requireNonNull(endpoint);
            Objects.requireNonNull(connectTimeout);
            Objects.requireNonNull(requestTimeout);
            String host = endpoint.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
            if (host == null || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !"/api/v1/telemetry".equals(endpoint.getRawPath())
                    || !("https".equalsIgnoreCase(endpoint.getScheme())
                        || ("http".equalsIgnoreCase(endpoint.getScheme()) && loopback))) {
                throw new IllegalArgumentException("Telemetry endpoint requires HTTPS and the telemetry route");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.length() > 4_096
                    || apiKey.chars().anyMatch(value -> value < 33 || value > 126)) {
                throw new IllegalArgumentException("Invalid telemetry API credential");
            }
            if (connectTimeout.compareTo(Duration.ofMillis(1)) < 0
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                    || requestTimeout.compareTo(Duration.ofMillis(1)) < 0
                    || requestTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Invalid telemetry HTTP timeout");
            }
        }

        static Configuration fromProperties() {
            String endpoint = setting("apocbr.telemetry.endpoint", "APOCBR_TELEMETRY_ENDPOINT");
            String apiKey = setting("apocbr.telemetry.apiKey", "APOCBR_TELEMETRY_API_KEY");
            if (endpoint == null || apiKey == null) {
                throw new IllegalArgumentException("Telemetry endpoint and API key are required");
            }
            return new Configuration(
                URI.create(endpoint),
                apiKey,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10)
            );
        }

        @Override
        public String toString() {
            return "TelemetryHttpConfiguration[redacted]";
        }

        private static String setting(String property, String environment) {
            String value = System.getenv(environment);
            return value == null || value.isBlank() ? System.getProperty(property) : value;
        }
    }

    private final Configuration configuration;
    private final HttpClient client;

    TelemetryHttpClient(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
        this.client = HttpClient.newBuilder()
            .connectTimeout(configuration.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    static TelemetryHttpClient configured() {
        return new TelemetryHttpClient(Configuration.fromProperties());
    }

    void post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(this.configuration.endpoint())
            .timeout(this.configuration.requestTimeout())
            .header("X-API-Key", this.configuration.apiKey())
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = this.client.send(
            request,
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                4_096
            )
        );
        if (response.statusCode() != 201) {
            throw new DeliveryException("HTTP_" + response.statusCode());
        }
        validateReceipt(response.body());
    }

    @Override
    public void close() {
        this.client.shutdownNow();
    }

    private static void validateReceipt(String body) throws DeliveryException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Invalid receipt");
            }
            JsonObject receipt = parsed.getAsJsonObject();
            if (!positiveInteger(receipt.get("snapshotId"))
                    || !positiveInteger(receipt.get("serverId"))
                    || !receipt.has("serverKey")
                    || !receipt.get("serverKey").isJsonPrimitive()
                    || !receipt.get("serverKey").getAsJsonPrimitive().isString()
                    || receipt.get("serverKey").getAsString().isBlank()) {
                throw new IllegalArgumentException("Invalid receipt");
            }
        } catch (RuntimeException invalidReceipt) {
            throw new DeliveryException("INVALID_ACKNOWLEDGMENT");
        }
    }

    private static boolean positiveInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return value.getAsBigDecimal().longValueExact() > 0;
        } catch (ArithmeticException invalidNumber) {
            return false;
        }
    }

    static final class DeliveryException extends IOException {
        private final String code;

        DeliveryException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return this.code;
        }
    }
}
