package zombie.network;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in server observation queue and delivery worker. Engine capture is owned by
 * ApocBRBackendBridgeHooks; no connections/worker start without an explicit adapter.
 * Hooks copy public values on their owning thread before calling publish*.
 * See BACKEND-BRIDGE.md for the source inventory and future integration contract.
 */
public final class ApocBRBackendBridge implements AutoCloseable {
    public static final String SOURCE = "pz-engine";
    public static final int SCHEMA_VERSION = 3;

    public enum State { NEW, RUNNING, STOPPED, FAILED }
    public enum EventType {
        PLAYER_CHAT, PLAYER_DEATH, PLAYER_CONNECTED, PLAYER_DISCONNECTED,
        POPULATION_SNAPSHOT, WORLD_CLOCK_SNAPSHOT, WEATHER_SNAPSHOT,
        HELICOPTER_STATE, CHARACTER_LEVEL_UP, RADIO_CONTENT_STATE,
        RADIO_LISTENER_REQUEST, RADIO_LINE_AIRED, WEATHER_CHANGED, POPULATION_CHANGED
    }

    public record Settings(int queueCapacity, int maxBatchSize, Duration batchWindow,
            Duration initialRetryDelay, Duration maxRetryDelay) {
        public Settings {
            if (queueCapacity < 1 || queueCapacity > 100_000
                    || maxBatchSize < 1 || maxBatchSize > Math.min(queueCapacity, 1_000)) {
                throw new IllegalArgumentException("Invalid queue/batch capacity");
            }
            checkDuration(batchWindow, Duration.ofMinutes(1));
            checkDuration(initialRetryDelay, Duration.ofMinutes(5));
            checkDuration(maxRetryDelay, Duration.ofMinutes(5));
            if (initialRetryDelay.compareTo(maxRetryDelay) > 0) {
                throw new IllegalArgumentException("Retry delays are out of order");
            }
        }

        public static Settings defaults() {
            return new Settings(1_024, 100, Duration.ofMillis(200),
                    Duration.ofSeconds(1), Duration.ofSeconds(30));
        }
    }

    /** UTC Unix milliseconds; gameTime is separate, source-formatted game time. */
    public record Context(long occurredAt, String gameTime, Long expiresAt) {
        public Context {
            if (occurredAt < 0 || (expiresAt != null && expiresAt <= occurredAt)) {
                throw new IllegalArgumentException("Invalid observation timestamps");
            }
            if (gameTime != null) gameTime = text(gameTime, 128);
        }
    }

    /** Server player name; the backend resolves character history using its mirrored data. */
    public record PublicCharacter(String characterName, String displayName) {
        public PublicCharacter {
            characterName = text(characterName, 128);
            displayName = text(displayName, 128);
        }
    }

    public record Weather(double temperatureC, double rainIntensity, double snowIntensity,
            double fogIntensity, double windSpeedKph, double windDirectionDegrees,
            boolean thunderstorm, boolean blizzard, String climateSeason,
            double dawnHour, double duskHour, double daylightHours) {
        public Weather {
            for (double value : new double[]{temperatureC, rainIntensity, snowIntensity,
                    fogIntensity, windSpeedKph, windDirectionDegrees, dawnHour, duskHour, daylightHours}) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid weather value");
            }
            climateSeason = text(climateSeason, 64);
        }
    }

    /** Flat, immutable public JSON fields; no live engine objects or arbitrary mod data. */
    public record Event(int schemaVersion, String serverKey, String source,
            String sourceEventId, EventType type, long occurredAt, String gameTime,
            Map<String, Object> publicPayload, Long expiresAt) {
        public Event {
            publicPayload = Map.copyOf(publicPayload);
        }
    }

    /**
     * Return only after the WHOLE batch is accepted/deduplicated or individual
     * permanent event rejections are accounted for by rejectedInBatch().
     * Throw on failure/uncertain acknowledgment: the same immutable batch is retried.
     * Implementations must use bounded timeouts and respond to interruption.
     * ApocBRBackendHttpDelivery supplies the manager HTTP/authentication mapping.
     */
    @FunctionalInterface
    public interface Delivery extends AutoCloseable {
        void deliver(List<Event> events) throws Exception;
        default int rejectedInBatch() { return 0; }
        @Override default void close() {}
    }

    /** Safe diagnostic code only; never include credentials, URLs or response bodies. */
    public static final class DeliveryFailure extends Exception {
        public final boolean permanent;
        public final long retryAfterMillis;

        public DeliveryFailure(String code, boolean permanent, long retryAfterMillis) {
            super(code != null && code.length() <= 48 && code.matches("[A-Z0-9_]+") ? code : "DELIVERY_ERROR");
            this.permanent = permanent;
            this.retryAfterMillis = Math.max(0, Math.min(3_600_000L, retryAfterMillis));
        }
    }

    /** Diagnostic counters only; these are not public radio observations. */
    public record Status(State state, int queued, int inFlight, long accepted,
            long droppedFull, long rejectedStopped, long delivered, long deliveryFailures,
            long rejectedDelivery, String lastDeliveryError) {}

    private final Settings settings;
    private final String serverKey;
    private final String sessionId = UUID.randomUUID().toString();
    private final ArrayBlockingQueue<Event> queue;
    private final LongAdder accepted = new LongAdder();
    private final LongAdder droppedFull = new LongAdder();
    private final LongAdder rejectedStopped = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder deliveryFailures = new LongAdder();
    private final LongAdder rejectedDelivery = new LongAdder();
    private volatile String lastDeliveryError;
    // Lifecycle/sequence lock is never held while batching, delivering, or waiting.
    private final Object lifecycle = new Object();
    private volatile State state = State.NEW;
    private volatile List<Event> inFlight = List.of();
    private volatile Thread worker;
    private long sequence;

    public ApocBRBackendBridge(String serverKey, Settings settings) {
        this.serverKey = text(serverKey, 128);
        this.settings = Objects.requireNonNull(settings);
        this.queue = new ArrayBlockingQueue<>(settings.queueCapacity());
    }

    /** Explicit, one-time start; construction/publication alone never starts a worker. */
    public void start(Delivery delivery) {
        Objects.requireNonNull(delivery);
        synchronized (this.lifecycle) {
            if (this.state != State.NEW) throw new IllegalStateException("Bridge already started/stopped");
            Thread thread = Thread.ofVirtual().name("ApocBR-Backend-Bridge").unstarted(() -> run(delivery));
            this.worker = thread;
            this.state = State.RUNNING;
            try {
                thread.start();
            } catch (RuntimeException | Error failure) {
                this.state = State.FAILED;
                throw failure;
            }
        }
    }

    /** General/global in-game chat only; the caller must verify actual sender identity. */
    public boolean publishChat(Context context, PublicCharacter author, String message) {
        return publishChat(context, author, message, "general");
    }

    public boolean publishChat(Context context, PublicCharacter author, String message, String channel) {
        if (!"general".equals(channel) && !"world".equals(channel)) {
            throw new IllegalArgumentException("Chat channel is not public");
        }
        Map<String, Object> payload = characterFields(author);
        payload.put("channel", channel);
        payload.put("origin", "game");
        payload.put("text", text(message, 2_048));
        return publish(context, EventType.PLAYER_CHAT, payload);
    }

    /** Unknown attacker stays absent; this does not claim an exact medical cause of death. */
    public boolean publishDeath(Context context, PublicCharacter victim, PublicCharacter killer) {
        Map<String, Object> payload = characterFields(victim);
        if (killer != null) {
            payload.put("killerCharacterName", killer.characterName());
            payload.put("killerDisplayName", killer.displayName());
        }
        return publish(context, EventType.PLAYER_DEATH, payload);
    }

    /** Character presence after identity resolution, not a raw socket/authentication event. */
    public boolean publishConnection(Context context, PublicCharacter character, boolean connected) {
        return publish(context, connected ? EventType.PLAYER_CONNECTED : EventType.PLAYER_DISCONNECTED,
                characterFields(character));
    }

    /** Null means unavailable, not zero. This counts loaded zombies, not the entire map. */
    public boolean publishPopulation(Context context, Integer playersOnline, Integer loadedZombieCount) {
        return publishPopulation(context, playersOnline, loadedZombieCount, false);
    }

    public boolean publishPopulation(Context context, Integer playersOnline, Integer loadedZombieCount, boolean changed) {
        return publishPopulation(context, playersOnline, loadedZombieCount, changed, null, null);
    }

    public boolean publishPopulation(Context context, Integer playersOnline, Integer loadedZombieCount,
            boolean changed, Integer previousPlayers, Integer previousZombies) {
        if ((playersOnline == null && loadedZombieCount == null)
                || (playersOnline != null && playersOnline < 0)
                || (loadedZombieCount != null && loadedZombieCount < 0)) {
            throw new IllegalArgumentException("Invalid population observation");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (playersOnline != null) payload.put("playersOnline", playersOnline);
        if (loadedZombieCount != null) payload.put("loadedZombieCount", loadedZombieCount);
        if (changed && previousPlayers != null) payload.put("previousPlayersOnline", previousPlayers);
        if (changed && previousZombies != null) payload.put("previousLoadedZombieCount", previousZombies);
        return publish(context, changed ? EventType.POPULATION_CHANGED : EventType.POPULATION_SNAPSHOT, payload);
    }

    public boolean publishWorldClock(Context context, double worldAgeHours) {
        return publishWorldClock(context, worldAgeHours, null);
    }

    public boolean publishWorldClock(Context context, double worldAgeHours, Long uptimeMillis) {
        if (!Double.isFinite(worldAgeHours) || worldAgeHours < 0) {
            throw new IllegalArgumentException("Invalid world age");
        }
        Objects.requireNonNull(context);
        if (context.gameTime() == null) throw new IllegalArgumentException("Game time is required");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("worldAgeHours", worldAgeHours);
        if (uptimeMillis != null) {
            if (uptimeMillis < 0) throw new IllegalArgumentException("Invalid uptime");
            payload.put("uptimeMillis", uptimeMillis);
        }
        return publish(context, EventType.WORLD_CLOCK_SNAPSHOT, payload);
    }

    public boolean publishWeather(Context context, Weather weather) {
        return publishWeather(context, weather, false);
    }

    public boolean publishWeather(Context context, Weather weather, boolean changed) {
        return publishWeather(context, weather, changed, null);
    }

    public boolean publishWeather(Context context, Weather weather, boolean changed, Weather previous) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("temperatureC", weather.temperatureC());
        payload.put("rainIntensity", weather.rainIntensity());
        payload.put("snowIntensity", weather.snowIntensity());
        payload.put("fogIntensity", weather.fogIntensity());
        payload.put("windSpeedKph", weather.windSpeedKph());
        payload.put("windDirectionDegrees", weather.windDirectionDegrees());
        payload.put("thunderstorm", weather.thunderstorm());
        payload.put("blizzard", weather.blizzard());
        payload.put("climateSeason", weather.climateSeason());
        payload.put("dawnHour", weather.dawnHour());
        payload.put("duskHour", weather.duskHour());
        payload.put("daylightHours", weather.daylightHours());
        if (changed && previous != null) {
            payload.put("previousTemperatureC", previous.temperatureC());
            payload.put("previousRainIntensity", previous.rainIntensity());
            payload.put("previousSnowIntensity", previous.snowIntensity());
            payload.put("previousFogIntensity", previous.fogIntensity());
            payload.put("previousWindSpeedKph", previous.windSpeedKph());
            payload.put("previousWindDirectionDegrees", previous.windDirectionDegrees());
            payload.put("previousThunderstorm", previous.thunderstorm());
            payload.put("previousBlizzard", previous.blizzard());
            payload.put("previousClimateSeason", previous.climateSeason());
        }
        return publish(context, changed ? EventType.WEATHER_CHANGED : EventType.WEATHER_SNAPSHOT, payload);
    }

    public boolean publishHelicopter(Context context, boolean active) {
        return publish(context, EventType.HELICOPTER_STATE, Map.of("active", active));
    }

    public boolean publishLevelUp(Context context, PublicCharacter character, String skillId,
            int previousLevel, int newLevel, boolean synchronizedState) {
        if (previousLevel < 0 || newLevel <= previousLevel || newLevel > 10) {
            throw new IllegalArgumentException("Invalid level increase");
        }
        Map<String, Object> payload = characterFields(character);
        payload.put("skillId", text(skillId, 128));
        payload.put("previousLevel", previousLevel);
        payload.put("newLevel", newLevel);
        payload.put("observationBasis", synchronizedState ? "received_xp_state" : "server_level_transition");
        return publish(context, EventType.CHARACTER_LEVEL_UP, payload);
    }

    public boolean publishRadioContent(Context context, String stationId, String contentId,
            String kind, String phase, long sequence, String caption) {
        if ((!"song".equals(kind) && !"talk".equals(kind)) || sequence < 0
                || !List.of("announced", "started", "completed", "stopped").contains(phase)) {
            throw new IllegalArgumentException("Invalid radio content state");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stationId", text(stationId, 128));
        payload.put("contentId", text(contentId, 128));
        payload.put("contentKind", kind);
        payload.put("phase", phase);
        payload.put("playbackSequence", sequence);
        if (caption != null && !caption.isBlank()) payload.put("caption", text(caption, 512));
        payload.put("observationBasis", "server_scheduler");
        return publish(context, EventType.RADIO_CONTENT_STATE, payload);
    }

    public boolean publishRadioRequest(Context context, String stationId, String characterName,
            String songIds, String message, int songCount, int queueDepth) {
        if (songCount < 1 || songCount > 25 || queueDepth < 0 || queueDepth > 100) {
            throw new IllegalArgumentException("Invalid radio request counts");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stationId", text(stationId, 128));
        payload.put("characterName", text(characterName, 128));
        payload.put("songIds", text(songIds, 4_096));
        if (message != null && !message.isBlank()) payload.put("message", text(message, 240));
        payload.put("songCount", songCount);
        payload.put("queueDepth", queueDepth);
        payload.put("observationBasis", "supplied_request_name");
        return publish(context, EventType.RADIO_LISTENER_REQUEST, payload);
    }

    public boolean publishRadioLine(Context context, String stationId, String kind, String line) {
        if (!List.of("announcer", "dedication", "catalog_talk", "request_notice").contains(kind)) {
            throw new IllegalArgumentException("Invalid radio line kind");
        }
        return publish(context, EventType.RADIO_LINE_AIRED, Map.of(
                "stationId", text(stationId, 128), "lineKind", kind,
                "text", text(line, 2_048), "observationBasis", "server_transmission"));
    }

    private boolean publish(Context context, EventType type, Map<String, Object> payload) {
        Objects.requireNonNull(context);
        synchronized (this.lifecycle) {
            if (this.state == State.STOPPED || this.state == State.FAILED) {
                this.rejectedStopped.increment();
                return false;
            }
            // Assignment and enqueue share the lock, preserving sequence/FIFO order
            // even when several engine threads publish concurrently. Gaps are allowed.
            Event event = new Event(SCHEMA_VERSION, this.serverKey, SOURCE,
                    this.sessionId + ":" + ++this.sequence, type, context.occurredAt(),
                    context.gameTime(), payload, context.expiresAt());
            if (!this.queue.offer(event)) {
                this.droppedFull.increment();
                return false;
            }
            this.accepted.increment();
            return true;
        }
    }

    private void run(Delivery delivery) {
        long retryNanos = this.settings.initialRetryDelay().toNanos();
        try {
            while (this.state == State.RUNNING) {
                if (this.inFlight.isEmpty()) collectBatch();
                if (this.state != State.RUNNING) break;
                try {
                    delivery.deliver(this.inFlight);
                    int rejected = delivery.rejectedInBatch();
                    if (rejected < 0 || rejected > this.inFlight.size()) {
                        throw new DeliveryFailure("INVALID_DELIVERY_ACCOUNTING", true, 0);
                    }
                    this.rejectedDelivery.add(rejected);
                    this.delivered.add(this.inFlight.size() - rejected);
                    this.lastDeliveryError = rejected == 0 ? null : "EVENT_REJECTED";
                    this.inFlight = List.of();
                    retryNanos = this.settings.initialRetryDelay().toNanos();
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    // Never log exception bodies: adapters may include secrets/payloads.
                    this.deliveryFailures.increment();
                    this.lastDeliveryError = failure instanceof DeliveryFailure detail ? detail.getMessage() : "DELIVERY_ERROR";
                    if (failure instanceof DeliveryFailure detail && detail.permanent) {
                        synchronized (this.lifecycle) {
                            if (this.state == State.RUNNING) this.state = State.FAILED;
                        }
                        break;
                    }
                    if (this.state != State.RUNNING) break;
                    long requestedDelay = failure instanceof DeliveryFailure detail
                            ? TimeUnit.MILLISECONDS.toNanos(detail.retryAfterMillis) : 0;
                    TimeUnit.NANOSECONDS.sleep(Math.max(retryNanos, requestedDelay));
                    retryNanos = Math.min(retryNanos * 2, this.settings.maxRetryDelay().toNanos());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try { delivery.close(); } catch (RuntimeException ignored) {}
            synchronized (this.lifecycle) {
                if (this.state == State.RUNNING) this.state = State.FAILED;
            }
        }
    }

    private void collectBatch() throws InterruptedException {
        ArrayList<Event> batch = new ArrayList<>(this.settings.maxBatchSize());
        batch.add(this.queue.take());
        long started = System.nanoTime();
        try {
            while (batch.size() < this.settings.maxBatchSize() && this.state == State.RUNNING) {
                long remaining = this.settings.batchWindow().toNanos() - (System.nanoTime() - started);
                if (remaining <= 0) break;
                Event next = this.queue.poll(remaining, TimeUnit.NANOSECONDS);
                if (next == null) break;
                batch.add(next);
            }
        } finally {
            // Also retain events removed before interruption during batch collection.
            this.inFlight = List.copyOf(batch);
        }
    }

    public State state() {
        return this.state;
    }

    /** Approximate during delivery; exact after awaitTermination succeeds. */
    public Status status() {
        return new Status(this.state, this.queue.size(), this.inFlight.size(), this.accepted.sum(),
                this.droppedFull.sum(), this.rejectedStopped.sum(), this.delivered.sum(),
                this.deliveryFailures.sum(), this.rejectedDelivery.sum(), this.lastDeliveryError);
    }

    /**
     * Terminal cancellation, not a flush. Outstanding observations remain in memory
     * until this instance is released; no durable spool/restart replay is provided.
     */
    @Override
    public void close() {
        synchronized (this.lifecycle) {
            this.state = State.STOPPED;
            if (this.worker != null) this.worker.interrupt();
        }
    }

    /** Must be called away from the game thread. Delivery must honor timeouts/interruption. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        checkDuration(timeout, Duration.ofMinutes(5));
        Thread thread = this.worker;
        if (thread == Thread.currentThread()) throw new IllegalStateException("Worker cannot join itself");
        return thread == null || thread.join(timeout);
    }

    private static Map<String, Object> characterFields(PublicCharacter character) {
        Objects.requireNonNull(character);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("characterName", character.characterName());
        payload.put("displayName", character.displayName());
        return payload;
    }

    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Invalid public text length");
        }
        return value;
    }

    private static void checkDuration(Duration duration, Duration max) {
        Objects.requireNonNull(duration);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(max) > 0) {
            throw new IllegalArgumentException("Invalid bridge duration");
        }
    }
}
