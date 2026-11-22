package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Captures detached window data on the server thread and encodes and delivers it asynchronously. */
public final class TelemetrySnapshotReporter {
    private static final long INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
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
        TelemetryJvmSnapshot.baseline();
        System.out.println("[ApocBR-Agent][Telemetry] Schema-3 delivery enabled; snapshots every 30 seconds.");
    }

    /** Called after the loop duration is recorded, so snapshot work is excluded from loop timing. */
    public static void onServerLoop() {
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
        Snapshot snapshot;
        try {
            int playersOnline = TelemetryWorldSnapshot.onlinePlayers();
            Map<String, Object> world = TelemetryWorldSnapshot.capture();
            Map<String, TelemetryRegistry.EventSnapshot> events = TelemetryRegistry.snapshotAndReset();
            TelemetryRegistry.TickSnapshot ticks = TelemetryRegistry.snapshotTicksAndReset();
            TelemetryRegistry.LoopSnapshot loops = TelemetryRegistry.snapshotLoopsAndReset();
            String gameVersion = AgentConfiguration.systemProperty(
                AgentConfiguration.TELEMETRY_GAME_VERSION_PROPERTY
            );
            if (gameVersion == null) {
                gameVersion = TelemetryWorldSnapshot.gameVersion();
            }
            snapshot = new Snapshot(
                gameVersion,
                intervalMillis,
                currentSequence,
                nowMillis,
                playersOnline,
                droppedCount,
                ticks,
                loops,
                world,
                events,
                OptimizationTelemetry.snapshotAndReset(),
                TelemetryJvmSnapshot.capture(),
                TickDiagnostics.snapshotAndReset(),
                AgentConfiguration.booleanProperty(AgentConfiguration.TELEMETRY_LUA_PROPERTY)
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
                .start(() -> deliver(current, snapshot));
        } catch (RuntimeException workerFailure) {
            dropped.increment();
            IN_FLIGHT.set(false);
            System.err.println("[ApocBR-Agent][Telemetry] Unable to start HTTP worker.");
        }
    }

    private static void deliver(TelemetryHttpClient delivery, Snapshot snapshot) {
        try {
            delivery.post(snapshot.encode());
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
        } catch (RuntimeException invalidSnapshot) {
            dropped.increment();
            System.err.println("[ApocBR-Agent][Telemetry] Snapshot encoding failed.");
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private record Snapshot(String gameVersion, long intervalMillis, long sequence, long nowMillis,
            int playersOnline, long droppedCount, TelemetryRegistry.TickSnapshot ticks,
            TelemetryRegistry.LoopSnapshot loops, Map<String, Object> world,
            Map<String, TelemetryRegistry.EventSnapshot> events, OptimizationTelemetry.Snapshot optimizations,
            Map<String, Object> jvm, TickDiagnostics.Report diagnostics, boolean callbackTiming) {
        String encode() {
            return TelemetrySnapshotPayload.encode(gameVersion, intervalMillis, sequence, nowMillis,
                playersOnline, droppedCount, ticks, loops, world, events, optimizations, jvm, diagnostics, callbackTiming);
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
