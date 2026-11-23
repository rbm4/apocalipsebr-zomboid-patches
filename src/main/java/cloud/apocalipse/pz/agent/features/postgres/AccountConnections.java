package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.Connection;
import java.sql.SQLException;

/** Nested native account calls share one per-thread pooled lease, never a game-class field. */
public final class AccountConnections {
    private final PostgresConnections pool;
    private final ThreadLocal<Lease> current = new ThreadLocal<>();

    private static final class Lease {
        final Thread owner = Thread.currentThread();
        final Connection connection;
        int depth;

        Lease(Connection connection) { this.connection = connection; }
    }

    public AccountConnections(PostgresConnections pool) { this.pool = pool; }

    public Call open() throws SQLException {
        ForegroundDatabase workflow = ForegroundDatabase.begin();
        try {
            Lease lease = current.get();
            if (lease == null) {
                lease = new Lease(pool.borrowAccount());
                current.set(lease);
            }
            return new Call(lease, ++lease.depth, workflow);
        } catch (SQLException | RuntimeException | Error failure) {
            workflow.close();
            throw failure;
        }
    }

    public Connection connection() {
        Lease lease = current.get();
        if (lease == null) throw new IllegalStateException("Account database call requires a scoped connection");
        return lease.connection;
    }

    public final class Call implements AutoCloseable {
        private final Lease lease;
        private final int depth;
        private final ForegroundDatabase workflow;
        private boolean closed;

        private Call(Lease lease, int depth, ForegroundDatabase workflow) {
            this.lease = lease;
            this.depth = depth;
            this.workflow = workflow;
        }

        @Override
        public void close() throws SQLException {
            if (Thread.currentThread() != lease.owner) {
                throw new IllegalStateException("Account database scope closed from another thread");
            }
            if (closed) return;
            if (current.get() != lease || lease.depth != depth) {
                throw new IllegalStateException("Account database scopes must close in reverse order");
            }
            closed = true;
            try {
                if (--lease.depth == 0) {
                    current.remove();
                    lease.connection.close();
                }
            } finally {
                workflow.close();
            }
        }
    }
}
