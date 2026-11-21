package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.SQLException;

/** Exception boundaries must never expose driver messages, row details or JDBC credentials. */
final class PostgresErrors {
    private PostgresErrors() { }

    static SQLException sanitized(String operation, SQLException failure) {
        String state = findState(failure);
        return new SQLException("PostgreSQL " + operation + " failed; SQLState=" + state,
            state, failure.getErrorCode());
    }

    private static String findState(SQLException failure) {
        var pending = new java.util.ArrayDeque<Throwable>();
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        // Driver/pool wrappers may carry the useful state in a cause or next exception.
        // Inspect only bounded structure and SQLState codes, never messages or raw causes.
        for (int count = 0; !pending.isEmpty() && count < 32; count++) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[A-Z0-9]{5}") && !"HY000".equals(state)) return state;
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return "HY000";
    }
}
