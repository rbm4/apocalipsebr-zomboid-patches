package cloud.apocalipse.pz.agent.features.telemetry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.http.JsonHttpTransport;
import java.io.IOException;
import java.net.URI;
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
                throw new IllegalArgumentException(
                    "Telemetry endpoint requires HTTPS, except for loopback HTTP, and the telemetry route"
                );
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
            AgentConfiguration agentConfiguration = AgentConfiguration.load();
            String endpoint = setting("apocbr.telemetry.endpoint", "APOCBR_TELEMETRY_ENDPOINT");
            String apiKey = setting("apocbr.telemetry.apiKey", "APOCBR_TELEMETRY_API_KEY");
            return fromSettings(
                endpoint,
                apiKey,
                System.getProperty("apocbr.bridge.serverKey"),
                agentConfiguration.telemetryEndpoint().toString()
            );
        }

        static Configuration fromSettings(String endpoint, String apiKey, String bridgeApiKey) {
            return fromSettings(
                endpoint,
                apiKey,
                bridgeApiKey,
                AgentConfiguration.load().telemetryEndpoint().toString()
            );
        }

        private static Configuration fromSettings(
                String endpoint,
                String apiKey,
                String bridgeApiKey,
                String configuredEndpoint) {
            String resolvedEndpoint = endpoint == null || endpoint.isBlank() ? configuredEndpoint : endpoint;
            String resolvedApiKey = apiKey == null || apiKey.isBlank() ? bridgeApiKey : apiKey;
            return new Configuration(
                URI.create(resolvedEndpoint),
                resolvedApiKey,
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
    private final JsonHttpTransport transport;

    TelemetryHttpClient(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration);
        this.transport = new JsonHttpTransport(configuration.connectTimeout());
    }

    static TelemetryHttpClient configured() {
        return new TelemetryHttpClient(Configuration.fromProperties());
    }

    void post(String body) throws IOException, InterruptedException {
        JsonHttpTransport.Response response = this.transport.post(
            this.configuration.endpoint(),
            this.configuration.apiKey(),
            "application/json; charset=utf-8",
            body,
            this.configuration.requestTimeout()
        );
        if (response.statusCode() != 201) {
            throw new DeliveryException("HTTP_" + response.statusCode());
        }
        validateReceipt(response.body());
    }

    @Override
    public void close() {
        this.transport.close();
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
