package zombie.savefile;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.network.GameServer;

/** Bounded administrative reads. Submit, update and shutdown belong to the server thread. */
public final class ApocBRAsyncServerDatabase {
    private static final int LIMIT = 32;
    private static final Map<UdpConnection, Pending<?>> pending = new IdentityHashMap<>();
    private static final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(LIMIT), task -> {
            Thread thread = new Thread(task, "ApocBR-Server-JDBC");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static boolean closed;

    private static final class Pending<T> {
        final UdpConnection connection;
        final String world, user, id;
        final long guid, steam, owner, expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        final CompletableFuture<T> result = new CompletableFuture<>();
        final Consumer<T> resume;

        Pending(UdpConnection connection, Consumer<T> resume) {
            this.connection = connection;
            this.resume = resume;
            world = Core.gameSaveWorld;
            user = connection.getUserName();
            id = connection.getIDStr();
            guid = connection.getConnectedGUID();
            steam = connection.getSteamId();
            owner = connection.getOwnerId();
        }

        boolean current() {
            return GameServer.udpEngine != null && GameServer.udpEngine.getActiveConnection(guid) == connection
                && !GameServer.isDelayedDisconnect(connection) && Objects.equals(world, Core.gameSaveWorld)
                && Objects.equals(user, connection.getUserName()) && Objects.equals(id, connection.getIDStr())
                && steam == connection.getSteamId() && owner == connection.getOwnerId();
        }

        void finish() { resume.accept(result.join()); }
    }

    /** One read per connection; busy/repeated requests are dropped without disconnecting an administrator. */
    public static <T> void submit(UdpConnection connection, Callable<T> work, Consumer<T> resume) {
        if (closed || pending.containsKey(connection) || pending.size() >= LIMIT) return;
        Pending<T> entry = new Pending<>(connection, resume);
        pending.put(connection, entry);
        try {
            io.execute(() -> {
                if (entry.result.isDone()) return;
                try (var budget = ApocBRForegroundDatabase.begin()) {
                    if (System.nanoTime() >= entry.expires) throw new java.util.concurrent.TimeoutException();
                    T value = work.call();
                    // Vanilla reads can catch SQLException and return an empty/partial list.
                    ApocBRForegroundDatabase.verifySuccess();
                    entry.result.complete(value);
                } catch (Exception | LinkageError failure) {
                    entry.result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException full) {
            pending.remove(connection);
        }
    }

    public static void update() {
        long started = System.nanoTime();
        int completed = 0;
        for (Pending<?> entry : new ArrayList<>(pending.values())) {
            if (!entry.current() || System.nanoTime() >= entry.expires) {
                pending.remove(entry.connection);
                entry.result.cancel(false);
                continue;
            }
            if (!entry.result.isDone()) continue;
            pending.remove(entry.connection);
            try {
                entry.finish();
            } catch (RuntimeException | LinkageError failure) {
                // Driver exceptions and query arguments may contain private account data.
                DebugLog.log("[ApocBR][Postgres] Administrative database request failed; response withheld");
            }
            if (++completed >= 2 || System.nanoTime() - started >= 2_000_000L) break;
        }
    }

    public static void shutdown() {
        closed = true;
        for (Pending<?> entry : pending.values()) entry.result.cancel(false);
        pending.clear();
        io.shutdownNow();
    }

    private ApocBRAsyncServerDatabase() {}
}
