package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import cloud.apocalipse.pz.agent.features.postgres.dictionary.DictionaryOrigin;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlayerJournalTest {
    @TempDir Path directory;
    @Test void initialCaptureReopensWithOpaqueProvenanceAndVersion() throws Exception {
        var origin = new DictionaryOrigin(2, UUID.randomUUID(), UUID.randomUUID());
        var operation = cloud.apocalipse.pz.agent.features.postgres.player.PlayerJournalPayloadFixture.operation(origin);
        UUID id;
        try (var journal = new PersistenceJournal(directory, 1024, value -> { })) { id = journal.submitDurable(operation); }
        try (var journal = new PersistenceJournal(directory, 1024, value -> { })) {
            assertEquals(id, journal.head().id());
            cloud.apocalipse.pz.agent.features.postgres.player.PlayerJournalPayloadFixture.verify((PlayerJournal.ReplayOperation) journal.head().operation(), origin);
            assertTrue(journal.pendingPlayer(operation.playerKey())); journal.acknowledgeCommitted(id); assertFalse(journal.pendingPlayer(operation.playerKey()));
        }
    }
    static Object zero(Class<?> type) { if (type == boolean.class) return false; if (type == int.class) return 0; if (type == long.class) return 0L; return null; }
    static final class Jdbc {
        volatile boolean online;
        final Set<UUID> receipts = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicBoolean loseCommit = new AtomicBoolean(true);
        final AtomicReference<SQLException> rejectWrite = new AtomicReference<>();
        Connection open() throws SQLException {
            if (!online) throw new SQLException("fixture offline", "08006");
            UUID[] receipt = {null}; boolean[] wrote = {false};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                if (m.getName().equals("prepareStatement")) {
                    String sql = (String) a[0]; Map<Integer, Object> bindings = new HashMap<>();
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (query, method, values) -> {
                        if (method.getName().startsWith("set")) { if (!method.getName().equals("setQueryTimeout")) bindings.put((Integer) values[0], values[1]); return null; }
                        if (method.getName().equals("executeQuery")) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (rows, read, args) -> zero(read.getReturnType()));
                        if (method.getName().equals("executeUpdate")) {
                            if (sql.contains("apocbr_persistence_receipt")) { receipt[0] = (UUID) bindings.get(1); return receipts.contains(receipt[0]) ? 0 : 1; }
                            SQLException failure = rejectWrite.getAndSet(null);
                            if (failure != null) throw failure;
                            wrote[0] = true; return 1;
                        }
                        return zero(method.getReturnType());
                    });
                }
                if (m.getName().equals("commit")) {
                    if (receipt[0] != null) { receipts.add(receipt[0]); if (wrote[0]) writes.incrementAndGet(); receipt[0] = null; wrote[0] = false; if (loseCommit.getAndSet(false)) throw new SQLException("commit acknowledgement lost", "08006"); }
                    return null;
                }
                if (m.getName().equals("rollback")) { receipt[0] = null; wrote[0] = false; return null; }
                if (m.getName().equals("isClosed")) return false;
                return zero(m.getReturnType());
            });
        }
    }
    static PlayerJournal.ReplayOperation snapshot() throws Exception {
        Class<?> type = Class.forName("cloud.apocalipse.pz.agent.features.postgres.player.PlayerNativeSave");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return (PlayerJournal.ReplayOperation) constructor.newInstance("world", "user", "", 0, "fixture", 1f, 2f, 0f, 251, false, "user", false,
            new byte[]{1, 2, 3}, new DictionaryOrigin(1, UUID.randomUUID(), UUID.randomUUID()));
    }
    @Test void durablePlayerCaptureSurvivesRestartAndLostCommitWithoutDuplicatingMutation() throws Exception {
        var jdbc = new Jdbc();
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
            (p, method, args) -> method.getName().equals("getConnection") ? jdbc.open() : zero(method.getReturnType()));
        var pool = new PostgresConnections(source, () -> { });
        PostgresRuntime.installForTest(new PostgresSettings(true, true, "jdbc:postgresql://fixture/unused", 1, 5000, 3000), pool);
        var operation = snapshot();
        try {
            PlayerJournal.start(directory); UUID id = PlayerJournal.submit(operation);
            assertFalse(PlayerJournal.readable(operation.playerKey())); PlayerJournal.shutdown();
            PlayerJournal.start(directory); assertFalse(PlayerJournal.readable(operation.playerKey())); jdbc.online = true;
            PlayerJournal.drain();
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!PlayerJournal.readable(operation.playerKey()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(PlayerJournal.readable(operation.playerKey())); assertEquals(Set.of(id), jdbc.receipts);
            assertEquals(1, jdbc.writes.get(), "receipt must suppress the retry after a committed-but-unacknowledged transaction");
        } finally { PlayerJournal.shutdown(); PostgresRuntime.shutdown(); }
    }

    @Test void duplicateKeyArchivesTheRejectedSaveAndReplaysTheNextSave() throws Exception {
        var jdbc = new Jdbc();
        jdbc.online = true;
        jdbc.loseCommit.set(false);
        var batch = new BatchUpdateException("private bind values", "23505", new int[0]);
        batch.setNextException(new org.postgresql.util.PSQLException(new org.postgresql.util.ServerErrorMessage(
            "C23505\0Dprivate key value\0spublic\0tnetworkplayers\0nnetworkplayers_pkey\0")));
        jdbc.rejectWrite.set(batch);
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
            (p, method, args) -> method.getName().equals("getConnection") ? jdbc.open() : zero(method.getReturnType()));
        PostgresRuntime.installForTest(new PostgresSettings(true, true, "jdbc:postgresql://fixture/unused", 1, 5000, 3000),
            new PostgresConnections(source, () -> { }));
        var operation = snapshot();
        UUID rejected, successor;
        try {
            PlayerJournal.start(directory);
            rejected = PlayerJournal.submit(operation);
            successor = PlayerJournal.submit(operation);
            PlayerJournal.drain();
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!PlayerJournal.readable(operation.playerKey()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(PlayerJournal.readable(operation.playerKey()));
            assertEquals(Set.of(successor), jdbc.receipts);
            assertEquals(1, jdbc.writes.get());
            assertTrue(Files.isRegularFile(directory.resolve("quarantine").resolve(rejected + ".record")));
        } finally { PlayerJournal.shutdown(); PostgresRuntime.shutdown(); }
        try (var journal = new PersistenceJournal(directory, 1024, value -> { })) {
            assertNull(journal.head());
            assertTrue(journal.quarantined(rejected));
        }
    }
}
