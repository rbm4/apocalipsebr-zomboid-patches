package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.sql.SQLException;

/** Never log driver messages, bound SQL values, JDBC URLs or exception causes. */
final class DictionaryDiagnostics {
    private DictionaryDiagnostics() { }
    static String sqlDiagnostic(Throwable failure, boolean ignored) {
        if (!(failure instanceof SQLException sql)) return "SQLState=HY000";
        String state = sql.getSQLState();
        return "SQLState=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "HY000")
            + "; errorCode=" + sql.getErrorCode();
    }
}
