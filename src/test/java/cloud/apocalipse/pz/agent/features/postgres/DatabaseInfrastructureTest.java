package cloud.apocalipse.pz.agent.features.postgres;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInfrastructureTest {
    static final class Jdbc {
        int borrowed, returned, shutdowns;
        boolean fail, failSetup, failClose;
        int timeout;
        final List<String> queries = new ArrayList<>();

        Object zero(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }

        DataSource source() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> {
                    if (m.getName().equals("getConnection")) { borrowed++; return connection(); }
                    return zero(m.getReturnType());
                });
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "toString": return "PRIVATE_JDBC_URL";
                        case "close": returned++; if (failClose) throw secret(); return null;
                        case "setSchema": if (failSetup) throw secret(); assertEquals("public", a[0]); return null;
                        case "setNetworkTimeout": timeout = (int) a[1]; return null;
                        case "prepareStatement": queries.add((String) a[0]); return statement((Connection) p);
                        default: return zero(m.getReturnType());
                    }
                });
        }

        PreparedStatement statement(Connection connection) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "setQueryTimeout": assertEquals(2, a[0]); return null;
                        case "getConnection": return connection;
                        case "executeQuery":
                            if (fail) throw secret();
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                                (rp, rm, ra) -> zero(rm.getReturnType()));
                        default: return zero(m.getReturnType());
                    }
                });
        }

        SQLException secret() { return new SQLException("PRIVATE_PASSWORD row DETAIL", "08006"); }
        PostgresConnections pool() { return new PostgresConnections(source(), () -> shutdowns++); }
    }

    @Test
    void nestedCallsShareLeaseAndOutOfOrderCloseCannotCorruptIt() throws Exception {
        Jdbc jdbc = new Jdbc();
        try (var pool = jdbc.pool()) {
            var accounts = new AccountConnections(pool);
            try (var outer = accounts.open()) {
                Connection connection = accounts.connection();
                try (var inner = accounts.open()) {
                    assertSame(connection, accounts.connection());
                    assertThrows(IllegalStateException.class, outer::close);
                    assertEquals(1, jdbc.borrowed);
                    assertEquals(0, jdbc.returned);
                }
                assertEquals(0, jdbc.returned);
            }
            assertEquals(1, jdbc.returned);
            assertThrows(IllegalStateException.class, accounts::connection);
        }
        assertEquals(1, jdbc.shutdowns);
    }

    @Test
    void expiredWorkflowStillReleasesConnectionAndDoesNotLeakIntoNextCall() throws Exception {
        Jdbc jdbc = new Jdbc();
        AtomicLong clock = new AtomicLong();
        try (var pool = jdbc.pool()) {
            var accounts = new AccountConnections(pool);
            try (var workflow = ForegroundDatabase.beginForTest(10, 250, clock::get);
                 var call = accounts.open()) {
                clock.set(11_000_000L);
                SQLException failure = assertThrows(SQLException.class,
                    () -> accounts.connection().prepareStatement("SELECT 1"));
                assertEquals("HYT00", failure.getSQLState());
                assertThrows(SQLException.class, ForegroundDatabase::verifySuccess);
            }
            assertEquals(1, jdbc.returned);
            try (var next = accounts.open()) { ForegroundDatabase.verifySuccess(); }
            assertEquals(2, jdbc.returned);
        }
    }

    @Test
    void nestedLoginScopeCannotExtendAnExistingDeadlineAndClosedScopesRejectWork() throws Exception {
        Jdbc jdbc = new Jdbc();
        AtomicLong clock = new AtomicLong();
        Connection stale;
        try (var outer = ForegroundDatabase.beginForTest(10, 250, clock::get)) {
            stale = ForegroundDatabase.wrap(jdbc.connection());
            try (var inner = ForegroundDatabase.beginForTest(5000, 3000, System::nanoTime)) {
                assertThrows(IllegalStateException.class, outer::close);
                clock.set(11_000_000L);
                assertEquals("HYT00", assertThrows(SQLException.class,
                    () -> stale.prepareStatement("SELECT 1")).getSQLState());
            }
        }
        assertEquals("08003", assertThrows(SQLException.class,
            () -> stale.prepareStatement("SELECT 1")).getSQLState());
    }

    @Test
    void databaseFailureSurvivesVanillaCatchAndNeverCarriesSecretsOrRawBackReferences() throws Exception {
        Jdbc jdbc = new Jdbc();
        try (var pool = jdbc.pool()) {
            var accounts = new AccountConnections(pool);
            try (var call = accounts.open()) {
                Connection connection = accounts.connection();
                assertFalse(connection.toString().contains("PRIVATE"));
                try (var statement = connection.prepareStatement("SELECT 1")) {
                    assertFalse(statement.getConnection().toString().contains("PRIVATE"));
                    assertThrows(SQLException.class, () -> statement.getConnection().unwrap(Connection.class));
                    jdbc.fail = true;
                    SQLException failure = assertThrows(SQLException.class, statement::executeQuery);
                    assertEquals("08006", failure.getSQLState());
                    assertNull(failure.getCause());
                    assertFalse(failure.toString().contains("PRIVATE"));
                    assertThrows(SQLException.class, ForegroundDatabase::verifySuccess);
                }
            }
        }
    }

    @Test
    void jdbcWorkflowCannotCrossThreads() throws Exception {
        Jdbc jdbc = new Jdbc();
        try (var pool = jdbc.pool()) {
            var accounts = new AccountConnections(pool);
            try (var call = accounts.open()) {
                Connection connection = accounts.connection();
                var result = CompletableFuture.supplyAsync(() -> {
                    assertThrows(IllegalStateException.class, () -> connection.prepareStatement("SELECT 1"));
                    assertThrows(IllegalStateException.class, call::close);
                    return true;
                });
                assertTrue(result.get());
            }
            assertEquals(1, jdbc.returned);
        }
    }

    @Test
    void failedSetupReleasesLeaseAndShutdownIsTerminal() throws Exception {
        Jdbc jdbc = new Jdbc();
        var pool = jdbc.pool();
        jdbc.failSetup = true;
        SQLException failure = assertThrows(SQLException.class, pool::borrowTransaction);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("PRIVATE"));
        assertEquals(1, jdbc.returned);
        pool.close();
        pool.close();
        assertEquals(1, jdbc.shutdowns);
        assertThrows(SQLException.class, pool::borrowTransaction);
    }

    @Test
    void foregroundBorrowWithoutScopeFailsBeforeTakingALease() throws Exception {
        Jdbc jdbc = new Jdbc();
        try (var pool = jdbc.pool()) {
            assertEquals("25000", assertThrows(SQLException.class, pool::borrowAccount).getSQLState());
            assertEquals(0, jdbc.borrowed);
        }
    }

    @Test
    void schemaValidationChecksAllAccountTablesWithoutDdl() throws Exception {
        Jdbc jdbc = new Jdbc();
        AccountSchema.validate(jdbc.connection());
        assertEquals(9, jdbc.queries.size());
        assertTrue(jdbc.queries.stream().allMatch(q -> q.startsWith("SELECT ") && q.endsWith("WHERE false")));
        jdbc.fail = true;
        SQLException failure = assertThrows(SQLException.class, () -> AccountSchema.validate(jdbc.connection()));
        assertTrue(failure.getMessage().contains("public.whitelist"));
        assertFalse(failure.toString().contains("PRIVATE"));
        assertNull(failure.getCause());
    }
}
