package zombie.apocalipse.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import zombie.Lua.LuaEventManager;
import zombie.core.Core;
import zombie.core.Translator;
import zombie.debug.DebugLog;
import zombie.network.GameServer;
import zombie.savefile.ApocBRPostgresConnector;

/** Server-only cached API. JDBC belongs exclusively to the bounded polling worker. */
public final class ApocBREventControl {
    static final long MAX_REVISION = 9_007_199_254_740_991L;
    static final String REGISTER = """
        INSERT INTO player_rel.lobby_event_control(server_key,world,engine_version,engine_heartbeat_at)
        VALUES (?,?,'42.21.0/event-control-v1',CURRENT_TIMESTAMP)
        ON CONFLICT(server_key,world) DO UPDATE
        SET engine_version=EXCLUDED.engine_version,engine_heartbeat_at=EXCLUDED.engine_heartbeat_at
        """;
    static final String READ = """
        SELECT desired_event_id,desired_revision,desired_name,desired_starts_at,desired_state,
               reported_event_id,reported_revision,reported_state
        FROM player_rel.lobby_event_control WHERE server_key=? AND world=?
        """;
    static final String RECEIPT = """
        UPDATE player_rel.lobby_event_control
        SET reported_event_id=?,reported_revision=?,reported_state='release_requested',reported_at=CURRENT_TIMESTAMP
        WHERE server_key=? AND world=? AND (reported_revision IS NULL OR reported_revision<=?)
        """;
    record Schedule(String id, long revision, String name, long startsAt, String state) {
        Schedule {
            if (revision < 0 || revision > MAX_REVISION || !java.util.Set.of("idle", "scheduled", "cancelled").contains(state))
                throw new IllegalArgumentException("Invalid event revision/state");
            if ("idle".equals(state)) {
                if (id != null || name != null || startsAt != 0) throw new IllegalArgumentException("Invalid idle schedule");
            } else {
                UUID.fromString(Objects.requireNonNull(id));
                if (revision == 0 || name == null || name.isBlank() || name.length() > 160
                    || name.codePoints().anyMatch(Character::isISOControl) || startsAt <= 0
                    || startsAt > 253_402_300_799_999L) throw new IllegalArgumentException("Invalid event schedule");
            }
        }
    }
    record Observed(Schedule schedule, Receipt reported, long nanos) {}
    record Receipt(String id, long revision) {}
    @FunctionalInterface interface Connections { Connection open() throws SQLException; }
    private static volatile ApocBREventControl instance;
    private final String serverKey, world;
    private final long pollMillis, freshNanos;
    private final ZoneId zone;
    private final Connections connections;
    private final LongSupplier wallClock, monotonic;
    private final AtomicReference<Observed> incoming = new AtomicReference<>();
    private final AtomicReference<Receipt> receipt = new AtomicReference<>();
    private volatile Observed active;
    private volatile boolean stopping;
    private boolean rejected;
    private boolean signalled;
    private Receipt applied;
    private Receipt restored;
    private Thread worker;
    private long nextSignalNanos, lastFailureNanos;

    ApocBREventControl(String serverKey, String world, long pollMillis, long freshMillis, ZoneId zone,
            Connections connections, LongSupplier wallClock, LongSupplier monotonic) {
        if (serverKey == null || !serverKey.matches("[a-zA-Z0-9_-]{1,80}") || world == null
            || world.isBlank() || world.length() > 128 || pollMillis < 1000 || pollMillis > 60000
            || freshMillis < pollMillis * 2 || freshMillis > 300000) throw new IllegalArgumentException("Invalid event configuration");
        this.serverKey = serverKey; this.world = world; this.pollMillis = pollMillis;
        this.freshNanos = freshMillis * 1_000_000L; this.zone = Objects.requireNonNull(zone);
        this.connections = connections; this.wallClock = wallClock; this.monotonic = monotonic;
    }

    public static synchronized void start() {
        if (!GameServer.server || instance != null || !Boolean.getBoolean("apocbr.events.enabled")) return;
        if (!Boolean.getBoolean("apocbr.postgres.enabled")) { log("POSTGRES_DISABLED; event control disabled"); return; }
        try {
            var control = new ApocBREventControl(System.getProperty("apocbr.events.serverKey", GameServer.serverName),
                Core.gameSaveWorld, Long.getLong("apocbr.events.pollMillis", 2000L),
                Long.getLong("apocbr.events.freshMillis", 10000L),
                ZoneId.of(System.getProperty("apocbr.events.displayZone", "America/Fortaleza")),
                ApocBRPostgresConnector::borrowServerConnection, System::currentTimeMillis, System::nanoTime);
            LuaEventManager.AddEvent("OnApocBRLobbyEventDue");
            instance = control;
            control.worker = new Thread(control::run, "ApocBR-event-control");
            control.worker.setDaemon(true);
            control.worker.start();
            log("STARTED; schedule polling is independent of online players");
        } catch (RuntimeException failure) { log("INVALID_CONFIGURATION; event control disabled"); }
    }

    private void run() {
        while (!stopping) {
            ApocBRPostgresConnector.runBackgroundDatabaseWork(() -> {
                try { pollOnce(); }
                catch (SQLException | RuntimeException failure) {
                    if (stopping) return;
                    long now = monotonic.getAsLong();
                    if (lastFailureNanos == 0 || now - lastFailureNanos >= 30_000_000_000L) {
                        lastFailureNanos = now;
                        log("POLL_RETRY; code=" + (failure instanceof SQLException sql ? sql.getSQLState() : "INVALID_SNAPSHOT"));
                    }
                }
            });
            try { Thread.sleep(pollMillis); } catch (InterruptedException ignored) { if (stopping) return; }
        }
    }

    /** Worker only. Fixed prepared SQL, bounded queries, caller-owned pool lease. No engine/Lua calls. */
    void pollOnce() throws SQLException {
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = connection.prepareStatement(REGISTER)) {
                statement.setQueryTimeout(2); statement.setString(1, serverKey); statement.setString(2, world);
                statement.executeUpdate();
            }
            Receipt pending = receipt.get();
            if (pending != null) {
                try (PreparedStatement statement = connection.prepareStatement(RECEIPT)) {
                    statement.setQueryTimeout(2); statement.setObject(1, UUID.fromString(pending.id)); statement.setLong(2, pending.revision);
                    statement.setString(3, serverKey); statement.setString(4, world);
                    statement.setLong(5, pending.revision);
                    statement.executeUpdate();
                }
                receipt.compareAndSet(pending, null); // an older receipt cannot overwrite a newer accepted revision
            }
            try (PreparedStatement statement = connection.prepareStatement(READ)) {
                statement.setQueryTimeout(2); statement.setString(1, serverKey); statement.setString(2, world);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing event control row", "02000");
                    Timestamp time = rows.getTimestamp(4);
                    Schedule schedule = new Schedule(rows.getString(1), rows.getLong(2), rows.getString(3),
                        time == null ? 0 : time.toInstant().toEpochMilli(), rows.getString(5));
                    String reportedId = rows.getString(6);
                    Receipt reported = null;
                    if (reportedId != null) {
                        UUID.fromString(reportedId);
                        long reportedRevision = rows.getLong(7);
                        if (reportedRevision < 1 || reportedRevision > schedule.revision
                            || !"release_requested".equals(rows.getString(8))) throw new IllegalArgumentException("Invalid release receipt");
                        reported = new Receipt(reportedId, reportedRevision);
                    }
                    incoming.set(new Observed(schedule, reported, monotonic.getAsLong()));
                }
            }
        }
    }

    /** Main thread only: worker snapshots are installed here, never midway through a Lua callback. */
    void acceptIncoming() {
        Observed next = incoming.getAndSet(null);
        if (next == null) return;
        if (active != null && (next.schedule.revision < active.schedule.revision
            || next.schedule.revision == active.schedule.revision && !next.schedule.equals(active.schedule))) {
            rejected = true; return;
        }
        active = next; rejected = false;
    }

    boolean fresh() {
        long age = active == null ? Long.MAX_VALUE : monotonic.getAsLong() - active.nanos;
        return !stopping && !rejected && active != null && age >= 0 && age <= freshNanos;
    }
    boolean due(String id, long revision) {
        Observed current = active;
        return fresh() && current.schedule.id != null && current.schedule.id.equals(id)
            && current.schedule.revision == revision && "scheduled".equals(current.schedule.state)
            && wallClock.getAsLong() >= current.schedule.startsAt;
    }
    boolean acknowledge(String id, long revision) {
        if (!authorized(id, revision)) return false;
        Receipt accepted = new Receipt(id, revision);
        if (!accepted.equals(applied)) {
            applied = accepted;
            if (!accepted.equals(active.reported)) receipt.set(accepted);
        }
        restored = active.reported;
        return true;
    }
    boolean authorized(String id, long revision) {
        return due(id, revision) || fresh() && new Receipt(id, revision).equals(active.reported);
    }

    public static void update() {
        ApocBREventControl control = current();
        if (control == null) return;
        control.acceptIncoming();
        Observed current = control.active;
        long now = control.monotonic.getAsLong();
        Receipt request = null;
        if (current != null && control.fresh()) {
            if (current.reported != null && !current.reported.equals(control.restored)
                && !current.reported.equals(control.applied)) request = current.reported;
            else if (control.due(current.schedule.id, current.schedule.revision)
                && !new Receipt(current.schedule.id, current.schedule.revision).equals(control.applied))
                request = new Receipt(current.schedule.id, current.schedule.revision);
        }
        if (request != null && (!control.signalled || now - control.nextSignalNanos >= 0)) {
            control.signalled = true;
            control.nextSignalNanos = now + 1_000_000_000L;
            LuaEventManager.triggerEvent("OnApocBRLobbyEventDue", request.id, (double)request.revision);
        }
    }

    private static ApocBREventControl current() { return GameServer.server ? instance : null; }
    public static String getStatus() {
        var control = current();
        return control == null ? "DISABLED" : control.active == null ? "STARTING" : control.fresh() ? "READY" : "STALE";
    }
    public static String getEventId() { var c = current(); return c == null || c.active == null ? null : c.active.schedule.id; }
    public static double getRevision() { var c = current(); return c == null || c.active == null ? 0 : c.active.schedule.revision; }
    public static String getEventName() { var c = current(); return c == null || c.active == null ? null : c.active.schedule.name; }
    public static double getStartsAtMillis() { var c = current(); return c == null || c.active == null ? 0 : c.active.schedule.startsAt; }
    public static String getScheduleState() { var c = current(); return c == null || c.active == null ? null : c.active.schedule.state; }
    public static boolean isDue(String id, double revision) {
        var c = current(); return c != null && validRevision(revision) && c.due(id, (long)revision);
    }
    public static boolean acknowledgeRelease(String id, double revision) {
        var c = current(); return c != null && validRevision(revision) && c.acknowledge(id, (long)revision);
    }
    public static boolean isReleaseAuthorized(String id, double revision) {
        var c = current(); return c != null && validRevision(revision) && c.authorized(id, (long)revision);
    }
    private static boolean validRevision(double value) { return Double.isFinite(value) && value > 0 && value <= MAX_REVISION && value == Math.rint(value); }

    /** Called at the existing guide speech boundary, on the simulation thread. */
    public static String guideGreeting(String greeting) {
        var c = current();
        if (c == null) return greeting;
        if (!c.fresh()) return greeting + " " + Translator.getText("IGUI_ApocExtractionMapUtils_EventUnavailable");
        Schedule event = c.active.schedule;
        if (!"scheduled".equals(event.state)) return greeting;
        String key;
        String detail;
        if (new Receipt(event.id, event.revision).equals(c.applied)) {
            key = "IGUI_ApocExtractionMapUtils_EventReleased"; detail = "";
        } else if (c.wallClock.getAsLong() >= event.startsAt) {
            key = "IGUI_ApocExtractionMapUtils_EventPending"; detail = "";
        } else {
            key = "IGUI_ApocExtractionMapUtils_EventScheduled";
            detail = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z", Locale.ROOT).withZone(c.zone).format(Instant.ofEpochMilli(event.startsAt));
        }
        return greeting + " " + Translator.getText(key, event.name, detail);
    }

    public static synchronized void shutdown() {
        var c = instance;
        if (c != null) { c.stopping = true; if (c.worker != null) c.worker.interrupt(); }
        instance = null;
    }
    private static void log(String code) { DebugLog.log("[ApocBR][Events] " + code); }
    private ApocBREventControl() { throw new AssertionError(); }
}
