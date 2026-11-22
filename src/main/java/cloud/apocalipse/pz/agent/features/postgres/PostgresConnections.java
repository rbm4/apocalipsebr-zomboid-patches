package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** Agent-owned pool. Callers own lease lifetime and transaction commit/rollback. */
public final class PostgresConnections implements AutoCloseable {
    private final DataSource source;
    private final AutoCloseable shutdown;
    private volatile boolean closed;

    public PostgresConnections(PostgresSettings settings) {
        if (!settings.enabled()) throw new IllegalStateException("PostgreSQL is disabled");
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(settings.jdbcUrl());
        pg.setConnectTimeout(3);
        pg.setSocketTimeout(3);
        pg.setTcpKeepAlive(true);
        pg.setApplicationName("ApocBR-game-persistence");
        HikariConfig config = new HikariConfig();
        config.setDataSource(pg);
        config.setPoolName("ApocBR-agent-persistence");
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(2000);
        config.setValidationTimeout(250);
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(false);
        config.setMaxLifetime(1_800_000);
        HikariDataSource pool = new HikariDataSource(config);
        source = pool;
        shutdown = pool;
    }

    PostgresConnections(DataSource source, AutoCloseable shutdown) {
        this.source = source;
        this.shutdown = shutdown;
    }

    String metrics() {
        if (!closed && source instanceof HikariDataSource dataSource && dataSource.getHikariPoolMXBean() instanceof HikariPool pool) {
            return "; poolTotal=" + pool.getTotalConnections() + "; poolActive=" + pool.getActiveConnections()
                + "; poolIdle=" + pool.getIdleConnections() + "; poolWaiting=" + pool.getThreadsAwaitingConnection();
        }
        return "; poolMetrics=UNAVAILABLE";
    }

    public Connection borrowTransaction() throws SQLException {
        return borrow(false, ForegroundDatabase.active());
    }

    public Connection borrowTransaction(int transactionIsolation) throws SQLException {
        return borrow(false, ForegroundDatabase.active(), transactionIsolation);
    }

    /** LISTEN owns a physical session outside the bounded query pool, including poolSize=1. */
    Connection openDictionaryListener() throws SQLException {
        if (closed) throw new SQLException("PostgreSQL pool is closed", "08003");
        DataSource physical = source instanceof HikariDataSource pool ? pool.getDataSource() : source;
        Connection connection = null;
        try {
            connection = physical.getConnection();
            if (closed) throw new SQLException("PostgreSQL pool is closed", "08003");
            connection.setAutoCommit(true);
            connection.setNetworkTimeout(Runnable::run, 10_000);
            return connection;
        } catch (SQLException failure) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            throw PostgresErrors.sanitized("dictionary listener acquisition", failure);
        }
    }

    Connection borrowAccount() throws SQLException {
        // Check the total nested workflow budget before waiting for the bounded pool.
        ForegroundDatabase.acquisitionMillis();
        return borrow(true, true);
    }

    private Connection borrow(boolean autoCommit, boolean foreground) throws SQLException {
        return borrow(autoCommit, foreground, null);
    }

    private Connection borrow(boolean autoCommit, boolean foreground, Integer transactionIsolation) throws SQLException {
        if (closed) throw new SQLException("PostgreSQL pool is closed", "08003");
        Connection connection = null;
        try {
            connection = foreground && source instanceof HikariDataSource pool
                ? ((HikariPool) pool.getHikariPoolMXBean()).getConnection(ForegroundDatabase.acquisitionMillis())
                : source.getConnection();
            if (closed) throw new SQLException("PostgreSQL pool is closed", "08003");
            if (foreground) connection = ForegroundDatabase.wrap(connection);
            connection.setAutoCommit(autoCommit);
            if (transactionIsolation != null) connection.setTransactionIsolation(transactionIsolation);
            connection.setSchema("public");
            return connection;
        } catch (SQLException failure) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            throw PostgresErrors.sanitized("connection acquisition", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            shutdown.close();
        } catch (Exception failure) {
            throw new IllegalStateException("PostgreSQL pool shutdown failed");
        }
    }
}
