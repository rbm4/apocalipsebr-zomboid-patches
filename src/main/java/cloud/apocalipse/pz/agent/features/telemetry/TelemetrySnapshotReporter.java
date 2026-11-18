package cloud.apocalipse.pz.agent.features.telemetry;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Captures a detached schema-3 payload on the server thread and delivers it asynchronously. */
public final class TelemetrySnapshotReporter {
    private static final long INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final String DEFAULT_GAME_VERSION = "42.22.2";
    private static final int MAX_ERROR_DETAIL_LENGTH = 180;
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern CREDENTIAL_PATTERN =
        Pattern.compile("(?i)(api[-_ ]?key|token|authorization)(\\s*[:=]\\s*|\\s+)[^\\s,;]+");
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final LongAdder dropped = new LongAdder();
    private static volatile TelemetryHttpClient client;
    private static long nextSnapshotNanos;
    private static long lastSnapshotNanos;
    private static long sequence;

    private TelemetrySnapshotReporter() {
    }

    static synchronized void initialize() {
        if (client != null) {
            return;
        }
        try {
            client = TelemetryHttpClient.configured();
        } catch (IllegalArgumentException invalidConfiguration) {
            System.out.println("[ApocBR-Agent][Telemetry] Delivery disabled; configure endpoint and associated API key.");
            return;
        }

        lastSnapshotNanos = System.nanoTime();
        nextSnapshotNanos = lastSnapshotNanos + INTERVAL_NANOS;
        System.out.println("[ApocBR-Agent][Telemetry] Schema-3 delivery enabled; snapshots every 30 seconds.");
    }

    /** Called by inlined Advice on the GameServer loop after reading server-owned counts. */
    public static void onServerLoop(int playersOnline) {
        TelemetryHttpClient current = client;
        if (current == null) {
            return;
        }

        long nowNanos = System.nanoTime();
        if (nowNanos - nextSnapshotNanos < 0 || !IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        long nowMillis = System.currentTimeMillis();
        long intervalMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nowNanos - lastSnapshotNanos));
        long currentSequence = ++sequence;
        long droppedCount = dropped.sumThenReset();
        String payload;
        try {
            Map<String, TelemetryRegistry.EventSnapshot> events = TelemetryRegistry.snapshotAndReset();
            String gameVersion = System.getProperty("apocbr.telemetry.gameVersion", DEFAULT_GAME_VERSION);
            payload = TelemetrySnapshotPayload.encode(
                gameVersion,
                intervalMillis,
                currentSequence,
                nowMillis,
                playersOnline,
                droppedCount,
                events
            );
        } catch (RuntimeException invalidSnapshot) {
            dropped.increment();
            IN_FLIGHT.set(false);
            System.err.println("[ApocBR-Agent][Telemetry] Snapshot construction failed.");
            nextSnapshotNanos = nowNanos + INTERVAL_NANOS;
            return;
        }

        lastSnapshotNanos = nowNanos;
        nextSnapshotNanos = nowNanos + INTERVAL_NANOS;
        try {
            Thread.ofVirtual().name("ApocBR-Telemetry-HTTP").inheritInheritableThreadLocals(false)
                .start(() -> deliver(current, payload));
        } catch (RuntimeException workerFailure) {
            dropped.increment();
            IN_FLIGHT.set(false);
            System.err.println("[ApocBR-Agent][Telemetry] Unable to start HTTP worker.");
        }
    }

    private static void deliver(TelemetryHttpClient delivery, String payload) {
        try {
            delivery.post(payload);
            System.out.println("[ApocBR-Agent][Telemetry] Snapshot accepted by API.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dropped.increment();
            System.err.println("[ApocBR-Agent][Telemetry] HTTP delivery interrupted.");
        } catch (IOException failure) {
            dropped.increment();
            System.err.println(
                "[ApocBR-Agent][Telemetry] HTTP delivery failed: " + formatDeliveryFailure(failure)
            );
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    static String formatDeliveryFailure(IOException failure) {
        if (failure instanceof TelemetryHttpClient.DeliveryException deliveryFailure) {
            return deliveryFailure.code();
        }

        Throwable cause = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable next = cause.getCause();
            if (next == null || next == cause) {
                break;
            }
            cause = next;
        }
        String detail = cause.getClass().getSimpleName();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getMessage();
        }
        if (message != null && !message.isBlank()) {
            message = URL_PATTERN.matcher(message).replaceAll("<url>");
            message = CREDENTIAL_PATTERN.matcher(message).replaceAll("$1$2<redacted>");
            message = message.replaceAll("[\\p{Cntrl}]+", " ").trim();
            if (message.length() > MAX_ERROR_DETAIL_LENGTH) {
                message = message.substring(0, MAX_ERROR_DETAIL_LENGTH - 3) + "...";
            }
            if (!message.isBlank()) {
                detail += ": " + message;
            }
        }
        return "TRANSPORT_ERROR (" + detail + ")";
    }
}
