package cloud.apocalipse.pz.agent.features.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionAcquisitionTest {
    @Test void configuredAcquisitionUsesRemainingWorkflowBudgetAndNestedCallsCannotExtendIt() throws Exception {
        AtomicLong clock = new AtomicLong();
        try (var login = ForegroundDatabase.beginForTest(5000, 3000, clock::get)) {
            assertEquals(3000, ForegroundDatabase.acquisitionMillis());
            try (var nested = ForegroundDatabase.beginForTest(30000, 30000, clock::get)) {
                clock.set(TimeUnit.MILLISECONDS.toNanos(3500));
                assertEquals(1500, ForegroundDatabase.acquisitionMillis());
            }
            clock.set(TimeUnit.MILLISECONDS.toNanos(5000));
            assertEquals("HYT00", assertThrows(SQLException.class, ForegroundDatabase::acquisitionMillis).getSQLState());
        }
        try (var ordinary = ForegroundDatabase.beginForTest(500, 250, clock::get)) {
            assertEquals(250, ForegroundDatabase.acquisitionMillis());
        }
    }

    @Test void coldHikariPoolMayEstablishFirstConnectionAfterOrdinary250msCap() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Connection physical = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                case "isValid" -> true;
                case "isClosed", "isReadOnly", "getAutoCommit" -> false;
                case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                case "getNetworkTimeout" -> 0;
                case "getSchema" -> "public";
                case "toString" -> "fixture-connection";
                default -> null;
            });
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{DataSource.class}, (p, m, a) -> {
                if (m.getName().equals("getConnection")) {
                    creating.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("Fixture release timed out", "HYT00");
                    return physical;
                }
                if (m.getName().equals("getLoginTimeout")) return 0;
                return null;
            });
        HikariConfig config = new HikariConfig();
        config.setDataSource(source);
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(1);
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(2000);
        config.setValidationTimeout(250);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try (HikariDataSource hikari = new HikariDataSource(config);
             PostgresConnections pool = new PostgresConnections(hikari, () -> { })) {
            Thread borrower = Thread.ofVirtual().start(() -> {
                try (var workflow = ForegroundDatabase.beginForTest(5000, 3000, System::nanoTime);
                     var connection = pool.borrowAccount()) {
                    assertEquals("public", connection.getSchema());
                    result.complete(null);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            try {
                assertTrue(creating.await(2, TimeUnit.SECONDS), "Hikari must attempt physical connection creation");
                // Deliberately hold creation past the old hardcoded timeout.
                Thread.sleep(400);
                assertFalse(result.isDone(), "Configured login budget should still be waiting after 250 ms");
                release.countDown();
                result.get(3, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                borrower.join(5_000);
            }
            assertEquals(0, hikari.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @Test void wrappedSqlStatesSurviveSanitizationWithoutRawDriverDetailsOrCycles() {
        SQLException wrapper = new SQLException("PRIVATE_POOL_URL", (String) null);
        SQLException driver = new SQLException("PRIVATE_PASSWORD", "28P01");
        wrapper.initCause(driver);
        driver.setNextException(wrapper);
        SQLException safe = PostgresErrors.sanitized("connection acquisition", wrapper);
        assertEquals("28P01", safe.getSQLState());
        assertFalse(safe.toString().contains("PRIVATE"));
        assertNull(safe.getCause());
        assertNull(safe.getNextException());
        SQLException nextOnly = new SQLException("PRIVATE", "HY000");
        nextOnly.setNextException(new SQLException("PRIVATE", "08001"));
        assertEquals("08001", PostgresErrors.sanitized("connection acquisition", nextOnly).getSQLState());
        SQLException cycle = new SQLException("PRIVATE", "invalid PRIVATE state");
        cycle.setNextException(cycle);
        assertEquals("HY000", PostgresErrors.sanitized("connection acquisition", cycle).getSQLState());
    }
}
