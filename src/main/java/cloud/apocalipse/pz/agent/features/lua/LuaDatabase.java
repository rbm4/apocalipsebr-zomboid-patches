package cloud.apocalipse.pz.agent.features.lua;

import cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime;
import java.sql.Connection;
import java.sql.SQLException;

/** Background-only event SQL uses the existing agent pool and autocommit policy. */
public final class LuaDatabase {
    private LuaDatabase() { }
    public static Connection borrow() throws SQLException {
        Connection connection = PostgresRuntime.borrowBackground();
        try { connection.setAutoCommit(true); return connection; }
        catch (SQLException failure) { connection.close(); throw failure; }
    }
    public static void background(Runnable work) { work.run(); }
}
