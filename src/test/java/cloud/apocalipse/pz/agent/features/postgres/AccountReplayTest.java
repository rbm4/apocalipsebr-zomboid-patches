package cloud.apocalipse.pz.agent.features.postgres;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountReplayTest {
    static final class Database {
        final Set<UUID> receipts = new HashSet<>();
        int writes, returned;
        boolean loseCommitResponse, failMutation;

        Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            return null;
        }

        DataSource source() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p,m,a) -> m.getName().equals("getConnection") ? connection() : zero(m.getReturnType()));
        }

        Connection connection() {
            UUID[] pendingReceipt = new UUID[1];
            int[] pendingWrites = new int[1];
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p,m,a) -> {
                    switch (m.getName()) {
                        case "setAutoCommit": assertEquals(false, a[0]); return null;
                        case "prepareStatement":
                            boolean claim = ((String)a[0]).startsWith("INSERT INTO public.apocbr_persistence_receipt");
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (sp,sm,sa) -> {
                                    if (sm.getName().equals("setQueryTimeout")) assertEquals(2, sa[0]);
                                    if (claim && sm.getName().equals("setObject")) pendingReceipt[0] = (UUID)sa[1];
                                    if (sm.getName().equals("executeUpdate")) {
                                        if (claim) {
                                            if (receipts.contains(pendingReceipt[0])) { pendingReceipt[0] = null; return 0; }
                                            return 1;
                                        }
                                        if (failMutation) throw new SQLException("PRIVATE_ROW_DATA", "23505");
                                        pendingWrites[0]++; return 1;
                                    }
                                    return zero(sm.getReturnType());
                                });
                        case "commit":
                            receipts.add(pendingReceipt[0]); writes += pendingWrites[0];
                            pendingReceipt[0] = null; pendingWrites[0] = 0;
                            if (loseCommitResponse) { loseCommitResponse = false; throw new SQLException("PRIVATE_URL", "08006"); }
                            return null;
                        case "rollback": pendingReceipt[0] = null; pendingWrites[0] = 0; return null;
                        case "close": returned++; return null;
                        default: return zero(m.getReturnType());
                    }
                });
        }

        PostgresConnections pool() { return new PostgresConnections(source(), () -> {}); }
    }

    private static PersistenceJournal.Entry entry() {
        return new PersistenceJournal.Entry(UUID.randomUUID(), System.currentTimeMillis(),
            new PersistenceOperations.Account(List.of(
                new PersistenceOperations.Sql("UPDATE whitelist SET role=? WHERE username=?", List.of(2,"fixture-user")),
                new PersistenceOperations.Sql("INSERT INTO userlog(username) VALUES (?)",List.of("fixture-user")))));
    }

    @Test
    void lostCommitReplyCannotDuplicateMutationsOnReplay() throws Exception {
        Database database = new Database();
        try (var pool = database.pool()) {
            AccountReplay replay = new AccountReplay(pool);
            var entry = entry();
            database.loseCommitResponse = true;
            var failure = assertThrows(SQLException.class, () -> replay.apply(entry));
            assertEquals("08006", failure.getSQLState());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("PRIVATE"));
            assertEquals(2, database.writes);
            assertEquals(AccountReplay.Result.ALREADY_COMMITTED, replay.apply(entry));
            assertEquals(2, database.writes);
            assertEquals(Set.of(entry.id()), database.receipts);
            assertEquals(2, database.returned);
        }
    }

    @Test
    void failedMutationRollsBackReceiptAndCanLaterRetry() throws Exception {
        Database database = new Database();
        try (var pool = database.pool()) {
            AccountReplay replay = new AccountReplay(pool);
            var entry = entry();
            database.failMutation = true;
            assertEquals("23505", assertThrows(SQLException.class, () -> replay.apply(entry)).getSQLState());
            assertTrue(database.receipts.isEmpty());
            assertEquals(0, database.writes);
            database.failMutation = false;
            assertEquals(AccountReplay.Result.COMMITTED, replay.apply(entry));
            assertEquals(2, database.writes);
            assertEquals(Set.of(entry.id()), database.receipts);
        }
    }
}
