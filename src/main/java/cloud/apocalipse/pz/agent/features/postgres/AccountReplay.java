package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Receipt claim and all account mutations commit atomically; no acknowledgement before this succeeds. */
final class AccountReplay {
    enum Result { COMMITTED, ALREADY_COMMITTED }
    private final PostgresConnections pool;

    AccountReplay(PostgresConnections pool) { this.pool = pool; }

    Result apply(PersistenceJournal.Entry entry) throws SQLException {
        if (!(entry.operation() instanceof PersistenceOperations.Account account)) {
            throw new SQLException("Journal operation has no registered replay adapter", "0A000");
        }
        try (Connection connection = pool.borrowTransaction()) {
            try {
                try (PreparedStatement receipt = connection.prepareStatement(
                        "INSERT INTO public.apocbr_persistence_receipt(operation_id) VALUES (?) ON CONFLICT (operation_id) DO NOTHING")) {
                    receipt.setQueryTimeout(2);
                    receipt.setObject(1, entry.id());
                    if (receipt.executeUpdate() == 0) {
                        connection.rollback();
                        return Result.ALREADY_COMMITTED;
                    }
                }
                for (PersistenceOperations.Sql operation : account.statements()) {
                    try (PreparedStatement statement = connection.prepareStatement(operation.sql())) {
                        statement.setQueryTimeout(2);
                        for (int i = 0; i < operation.parameters().size(); i++) {
                            statement.setObject(i + 1, operation.parameters().get(i));
                        }
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return Result.COMMITTED;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("Account journal operation failed", "HY000");
            }
        } catch (SQLException failure) {
            throw PostgresErrors.sanitized("account replay", failure);
        }
    }
}
