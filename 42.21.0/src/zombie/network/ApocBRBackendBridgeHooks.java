package zombie.network;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.debug.DebugLog;
import zombie.erosion.season.ErosionSeason;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

/** Engine-thread capture only. All delivery remains behind the bridge's worker. */
public final class ApocBRBackendBridgeHooks {
    private static volatile ApocBRBackendBridge bridge;
    private static volatile ApocBRBackendHttpDelivery httpDelivery;
    private static final LongAdder captureFailures = new LongAdder();
    private static long startedNanos;
    private static long nextSnapshotNanos;
    private static long snapshotIntervalMillis = 300_000L;
    private static long nextClockNanos, nextWeatherNanos, nextPopulationNanos;
    private static String clockPeriod;
    private static ApocBRBackendBridge.Weather lastWeather;
    private static boolean raining, snowing;
    private static Integer lastPlayers, lastZombies;
    private static Boolean helicopterActive;

    private ApocBRBackendBridgeHooks() {}

    /** Called once after world startup; enabled delivery runs only on its virtual worker. */
    public static void initialize() {
        if (!GameServer.server || bridge != null) return;
        if (!Boolean.getBoolean("apocbr.bridge.enabled")) {
            DebugLog.log("[ApocBR][Bridge] disabled; enable with -Dapocbr.bridge.enabled=true on the server JVM");
            return;
        }
        ApocBRBackendBridge instance = null;
        ApocBRBackendHttpDelivery delivery = null;
        try {
            String serverKey = System.getProperty("apocbr.bridge.serverKey");
            boolean captureOnly = Boolean.getBoolean("apocbr.bridge.captureOnly");
            if (serverKey == null) throw new IllegalArgumentException("Missing server key");
            serverKey = serverKey.strip().toLowerCase(Locale.ROOT);
            if (serverKey.length() > 80 || !serverKey.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid manager event scope");
            }
            snapshotIntervalMillis = Math.max(1_000L, Math.min(300_000L,
                    Long.getLong("apocbr.bridge.snapshotIntervalMs", 300_000L)));
            instance = new ApocBRBackendBridge(serverKey,
                    ApocBRBackendBridge.Settings.defaults());
            if (!captureOnly) {
                delivery = new ApocBRBackendHttpDelivery(ApocBRBackendHttpDelivery.Configuration.fromProperties(),
                        code -> DebugLog.log("[ApocBR][Bridge] delivery: " + code));
                instance.start(delivery);
            }
            install(instance);
            httpDelivery = delivery;
            DebugLog.log(captureOnly ? "[ApocBR][Bridge] capture-only mode enabled"
                    : "[ApocBR][Bridge] capture and HTTP delivery enabled");
        } catch (RuntimeException invalidConfiguration) {
            if (instance != null) instance.close();
            if (delivery != null) delivery.close();
            captureFailures.increment();
            DebugLog.log("[ApocBR][Bridge] invalid capture configuration; bridge remains disabled");
        }
    }

    /** Integration/testing entry point; call on the server main thread before publishing. */
    public static synchronized void install(ApocBRBackendBridge instance) {
        if (!GameServer.server || instance == null || bridge != null) {
            throw new IllegalStateException("Bridge requires a server and a single owner");
        }
        startedNanos = System.nanoTime();
        nextSnapshotNanos = 0;
        nextClockNanos = nextWeatherNanos = nextPopulationNanos = 0;
        clockPeriod = null;
        lastWeather = null;
        raining = snowing = false;
        lastPlayers = lastZombies = null;
        helicopterActive = null;
        bridge = instance;
    }

    public static ApocBRBackendBridge current() {
        return GameServer.server ? bridge : null;
    }

    public static long captureFailures() {
        return captureFailures.sum();
    }

    public static ApocBRBackendHttpDelivery.Status httpStatus() {
        ApocBRBackendHttpDelivery delivery = httpDelivery;
        return delivery == null ? null : delivery.status();
    }

    public static void shutdown() {
        ApocBRBackendBridge current = bridge;
        if (current != null) current.close();
    }

    private static ApocBRBackendBridge accepting() {
        ApocBRBackendBridge current = current();
        if (current == null) return null;
        ApocBRBackendBridge.State state = current.state();
        return state == ApocBRBackendBridge.State.NEW || state == ApocBRBackendBridge.State.RUNNING ? current : null;
    }

    static ApocBRBackendBridge.Context context(long ttlMillis) {
        long now = System.currentTimeMillis();
        GameTime time = GameTime.getInstance();
        String gameTime = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d",
                time.getYear(), time.getMonth() + 1, time.getDay() + 1,
                (int)time.getTimeOfDay(), time.getMinutes());
        return new ApocBRBackendBridge.Context(now, gameTime, ttlMillis > 0 ? now + ttlMillis : null);
    }

    private static boolean online(IsoPlayer player) {
        return player != null && !player.isAnimal()
                && GameServer.IDToPlayerMap.get(player.getOnlineID()) == player;
    }

    private static ApocBRBackendBridge.PublicCharacter character(IsoPlayer player) {
        return new ApocBRBackendBridge.PublicCharacter(player.getUsername(), player.getDisplayName());
    }

    /** The name carried by a packet must resolve to a player actually owned by its sender. */
    public static void onChat(IConnection connection, String author, String message, String channel) {
        ApocBRBackendBridge current = accepting();
        if (current == null || connection == null) return;
        try {
            for (int slot = 0; slot < 4; slot++) {
                IsoPlayer player = connection.getPlayerAt(slot);
                if (online(player) && (player.getUsername().equals(author) || player.getDisplayName().equals(author))) {
                    current.publishChat(context(0), character(player), message, channel);
                    return;
                }
            }
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    public static void onConnection(IsoPlayer player, boolean connected) {
        ApocBRBackendBridge current = accepting();
        if (current == null || !online(player)) return;
        try {
            current.publishConnection(context(0), character(player), connected);
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    /** IsoPlayer.onKilled is already protected by the engine's isOnKillDone guard. */
    public static void onDeath(IsoPlayer player, IsoGameCharacter killer) {
        ApocBRBackendBridge current = accepting();
        if (current == null || !online(player)) return;
        try {
            ApocBRBackendBridge.PublicCharacter attacker = killer instanceof IsoPlayer other && !other.isAnimal()
                    ? character(other) : null;
            current.publishDeath(context(0), character(player), attacker);
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    public static void onLevelUp(IsoGameCharacter character, PerkFactory.Perk perk,
            int previousLevel, int newLevel, boolean synchronizedState) {
        ApocBRBackendBridge current = accepting();
        if (current == null || !(character instanceof IsoPlayer player) || !online(player)
                || player.isDead() || newLevel <= previousLevel || previousLevel < 0 || newLevel > 10) return;
        try {
            current.publishLevelUp(context(0), character(player), perk.getId(), previousLevel, newLevel, synchronizedState);
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    /** Called only after XP state has loaded successfully, never while partially parsing it. */
    public static void onPerksLoaded(IsoGameCharacter character, Map<PerkFactory.Perk, Integer> previous) {
        if (accepting() == null || !(character instanceof IsoPlayer player) || !online(player)) return;
        try {
            for (IsoGameCharacter.PerkInfo info : character.getPerkList()) {
                onLevelUp(character, info.perk, previous.getOrDefault(info.perk, 0), info.level, true);
            }
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    /** Uses telemetry's existing capture; never scans zombie collections again. */
    public static void onPopulation(int playersOnline, Integer loadedZombieCount) {
        onPopulation(playersOnline, loadedZombieCount, System.nanoTime());
    }

    static void onPopulation(int playersOnline, Integer loadedZombieCount, long now) {
        ApocBRBackendBridge current = accepting();
        if (current == null || (nextPopulationNanos != 0 && now - nextPopulationNanos < 0)) return;
        try {
            boolean changed = significantCount(lastPlayers, playersOnline, 3)
                    || significantCount(lastZombies, loadedZombieCount, 100);
            if (current.publishPopulation(context(changed ? 0 : 360_000L), playersOnline, loadedZombieCount,
                    changed, lastPlayers, lastZombies)) {
                nextPopulationNanos = now + Duration.ofMinutes(2).toNanos();
                lastPlayers = playersOnline;
                lastZombies = loadedZombieCount;
            }
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    /** Existing helicopter network updates call this; positions/targets are discarded. */
    public static void onHelicopter(boolean active) {
        ApocBRBackendBridge current = accepting();
        if (current == null) return;
        try {
            if (helicopterActive == null) {
                helicopterActive = active; // Establish a baseline without inventing a transition.
            } else if (helicopterActive != active) {
                if (current.publishHelicopter(context(0), active)) helicopterActive = active;
            }
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    /** Called on simulation ticks; expensive capture is bounded by a real-time interval. */
    public static void onTick() {
        if (bridge == null || !GameServer.server) return;
        onTick(System.nanoTime());
    }

    static void onTick(long now) {
        // Avoid status allocation or clock reads on every tick when capture is disabled.
        if (bridge == null || !GameServer.server) return;
        if (nextSnapshotNanos != 0 && now - nextSnapshotNanos < 0) return;
        nextSnapshotNanos = now + Duration.ofSeconds(1).toNanos();
        ApocBRBackendBridge current = accepting();
        if (current == null) return;
        try {
            long ttl = snapshotIntervalMillis * 3;
            GameTime time = GameTime.getInstance();
            int hour = (int)time.getTimeOfDay();
            String period = time.getYear() + ":" + time.getMonth() + ":" + time.getDay() + ":"
                    + (hour < 6 || hour >= 22 ? "night" : hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening");
            if (nextClockNanos == 0 || now - nextClockNanos >= 0 || !period.equals(clockPeriod)) {
                if (current.publishWorldClock(context(ttl), time.getWorldAgeHours(),
                        Math.max(0, (now - startedNanos) / 1_000_000L))) {
                    nextClockNanos = now + Duration.ofMillis(snapshotIntervalMillis).toNanos();
                    clockPeriod = period;
                }
            }
            if (IsoWorld.instance != null && IsoWorld.instance.helicopter != null) {
                onHelicopter(IsoWorld.instance.helicopter.isActive());
            }
            ClimateManager climate = ClimateManager.getInstance();
            if (climate != null) {
                ErosionSeason season = climate.getSeason();
                if (season != null) {
                    ApocBRBackendBridge.Weather weather = new ApocBRBackendBridge.Weather(
                            climate.getTemperature(), climate.getRainIntensity(), climate.getSnowIntensity(),
                            climate.getFogIntensity(), climate.getWindspeedKph(), climate.getWindAngleDegrees(),
                            climate.getIsThunderStorming(), climate.getWeatherPeriod().isBlizzard(),
                            climate.getSeasonName(), season.getDawn(), season.getDusk(), season.getDaylight());
                    boolean rain = weather.rainIntensity() >= (raining ? .01 : .05);
                    boolean snow = weather.snowIntensity() >= (snowing ? .01 : .05);
                    boolean changed = lastWeather != null && (rain != raining || snow != snowing
                            || meaningfulWeather(lastWeather, weather));
                    if (changed || nextWeatherNanos == 0 || now - nextWeatherNanos >= 0) {
                        if (current.publishWeather(context(changed ? 0 : ttl), weather, changed, lastWeather)) {
                            lastWeather = weather;
                            raining = rain;
                            snowing = snow;
                            nextWeatherNanos = now + Duration.ofMillis(snapshotIntervalMillis).toNanos();
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            captureFailures.increment();
        }
    }

    private static boolean significantCount(Integer previous, Integer value, int minimum) {
        return previous != null && value != null
                && Math.abs((long)value - previous) >= Math.max(minimum, Math.ceil(previous * .25));
    }

    private static boolean meaningfulWeather(ApocBRBackendBridge.Weather before, ApocBRBackendBridge.Weather after) {
        double angle = Math.abs(before.windDirectionDegrees() - after.windDirectionDegrees()) % 360;
        return before.thunderstorm() != after.thunderstorm() || before.blizzard() != after.blizzard()
                || !before.climateSeason().equals(after.climateSeason())
                || Math.abs(before.temperatureC() - after.temperatureC()) >= 2
                || Math.abs(before.rainIntensity() - after.rainIntensity()) >= .2
                || Math.abs(before.snowIntensity() - after.snowIntensity()) >= .2
                || Math.abs(before.fogIntensity() - after.fogIntensity()) >= .2
                || Math.abs(before.windSpeedKph() - after.windSpeedKph()) >= 10
                || (after.windSpeedKph() >= 10 && Math.min(angle, 360 - angle) >= 45);
    }
}
