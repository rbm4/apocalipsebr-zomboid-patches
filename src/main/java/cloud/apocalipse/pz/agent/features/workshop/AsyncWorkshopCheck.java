package cloud.apocalipse.pz.agent.features.workshop;

import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry.Phase;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import zombie.Lua.LuaManager;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.ISteamWorkshopCallback;
import zombie.core.znet.SteamUGCDetails;
import zombie.core.znet.SteamUtils;
import zombie.core.znet.SteamWorkshop;
import zombie.core.znet.SteamWorkshopItem.ItemState;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.chat.ChatServer;

/** One asynchronous Steam query, advanced by the same thread that pumps vanilla Steam callbacks. */
public final class AsyncWorkshopCheck {
    private static final ReentrantLock STEAM_LOOP = new ReentrantLock();
    private static final Set<UdpConnection> REQUESTERS = new LinkedHashSet<>();
    private static final int MAX_REQUESTERS = 32;
    private static final int RESULTS_PER_PUMP = 8;
    private static final long RESULT_BUDGET_NANOS = 1_000_000L;
    private static final long TIMEOUT_NANOS = Duration.ofMinutes(2).toNanos();
    private static final String FAILED = "CheckModsNeedUpdate: Check not completed";
    private static final String CURRENT = "CheckModsNeedUpdate: Mods updated";
    private static final String UPDATE = "CheckModsNeedUpdate: Mods need update";

    private static Query query;
    private static volatile boolean closed;

    private AsyncWorkshopCheck() {}

    public static boolean enqueue(Object connection) {
        if (!GameServer.server || !SteamUtils.isSteamModeEnabled()) return false;
        synchronized (REQUESTERS) {
            if (!closed && REQUESTERS.size() < MAX_REQUESTERS) {
                REQUESTERS.add((UdpConnection) connection);
            }
        }
        return true;
    }

    public static boolean beforeSteamLoop() {
        if (!hasRequest() || Thread.currentThread() != GameServer.mainThread || !GameServer.server
                || !SteamUtils.isSteamModeEnabled()) return false;
        STEAM_LOOP.lock();
        return true;
    }

    public static void afterSteamLoop(boolean successful) {
        try {
            if (hasRequest()) {
                if (successful) advance();
                else finish(FAILED);
            }
        } catch (RuntimeException | LinkageError failure) {
            finish(FAILED);
        } finally {
            try {
                if (closed) releaseDuringShutdown();
            } finally {
                STEAM_LOOP.unlock();
            }
        }
    }

    private static boolean hasRequest() {
        synchronized (REQUESTERS) {
            return !closed && !REQUESTERS.isEmpty();
        }
    }

    private static void advance() {
        long started = System.nanoTime();
        try {
            if (query == null) {
                DebugType.Mod.println("CheckModsNeedUpdate: Checking...");
                query = new Query(started);
                long[] ids = LuaManager.GlobalObject.getSteamWorkshopItemIDs().stream()
                    .mapToLong(SteamUtils::convertStringToSteamID).filter(id -> id != -1L).toArray();
                query.handle = SteamWorkshop.instance.CreateQueryUGCDetailsRequest(ids, query);
                if (query.handle == 0) finish(FAILED);
                return;
            }
            if (query.failed || started - query.started >= TIMEOUT_NANOS) {
                finish(FAILED);
                return;
            }
            if (query.resultCount < 0) return;

            for (int processed = 0; query.index < query.resultCount; processed++) {
                if (processed >= RESULTS_PER_PUMP || (processed > 0 && System.nanoTime() - started >= RESULT_BUDGET_NANOS)) {
                    return;
                }
                SteamUGCDetails details = SteamWorkshop.instance.GetQueryUGCResult(query.handle, query.index++);
                if (details != null && needsUpdate(details)) {
                    finish(UPDATE);
                    return;
                }
            }
            finish(CURRENT);
        } finally {
            Phase.WORKSHOP_CHECK.recordSince(started);
        }
    }

    private static boolean needsUpdate(SteamUGCDetails details) {
        long id = details.getID();
        long state = SteamWorkshop.instance.GetItemState(id);
        return ItemState.NeedsUpdate.and(state)
            || (ItemState.Installed.and(state) && details.getTimeCreated() != 0
                && details.getTimeUpdated() != SteamWorkshop.instance.GetItemInstallTimeStamp(id));
    }

    private static void finish(String message) {
        Set<UdpConnection> recipients;
        synchronized (REQUESTERS) {
            recipients = new LinkedHashSet<>(REQUESTERS);
            REQUESTERS.clear();
        }
        try {
            releaseQuery();
        } catch (RuntimeException | LinkageError failure) {
            DebugType.Mod.println("CheckModsNeedUpdate: Could not release query");
        }
        if (UPDATE.equals(message)) ModUpdateRestartNotifier.onUpdateDetected();
        DebugType.Mod.println(message);
        for (UdpConnection connection : recipients) {
            if (connection == null) continue;
            try {
                ChatServer.getInstance().sendMessageToServerChat(connection, message);
            } catch (RuntimeException | LinkageError failure) {
                DebugType.Mod.println("CheckModsNeedUpdate: Could not report query result to chat");
            }
        }
    }

    private static void releaseQuery() {
        Query completed = query;
        query = null;
        if (completed == null) return;
        try {
            SteamWorkshop.instance.RemoveCallback(completed);
        } finally {
            if (completed.handle != 0) SteamWorkshop.instance.ReleaseQueryUGCRequest(completed.handle);
        }
    }

    public static void shutdown() {
        synchronized (REQUESTERS) {
            closed = true;
            REQUESTERS.clear();
        }
        // System.exit can have suspended the Steam owner while waiting for the JVM hook.
        if (!STEAM_LOOP.tryLock()) return;
        try {
            releaseDuringShutdown();
        } finally {
            STEAM_LOOP.unlock();
        }
    }

    private static void releaseDuringShutdown() {
        try {
            releaseQuery();
        } catch (RuntimeException | LinkageError failure) {
            DebugType.Mod.println("CheckModsNeedUpdate: Could not release query during shutdown");
        }
    }

    private static final class Query implements ISteamWorkshopCallback {
        final long started;
        long handle;
        int resultCount = -1;
        int index;
        boolean failed;

        Query(long started) { this.started = started; }

        @Override public void onItemQueryCompleted(long handle, int count) {
            if (this.handle == handle) resultCount = count;
        }

        @Override public void onItemQueryNotCompleted(long handle, int result) {
            if (this.handle == handle) failed = true;
        }

        @Override public void onItemCreated(long id, boolean needsAgreement) {}
        @Override public void onItemNotCreated(int result) {}
        @Override public void onItemUpdated(boolean needsAgreement) {}
        @Override public void onItemNotUpdated(int result) {}
        @Override public void onItemSubscribed(long id) {}
        @Override public void onItemNotSubscribed(long id, int result) {}
        @Override public void onItemDownloaded(long id) {}
        @Override public void onItemNotDownloaded(long id, int result) {}
    }
}
