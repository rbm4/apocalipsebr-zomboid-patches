package cloud.apocalipse.pz.agent.features.postgres;

import java.sql.DriverManager;

/** Run after package, with only the shaded agent and test classes on the classpath. No network I/O. */
public final class PostgresPackagingSmokeMain {
    private PostgresPackagingSmokeMain() { }

    public static void main(String[] arguments) throws Exception {
        Class.forName("cloud.apocalipse.pz.internal.postgresql.ds.PGSimpleDataSource").getConstructor().newInstance();
        Class.forName("cloud.apocalipse.pz.internal.hikari.HikariConfig").getConstructor().newInstance();
        String driver = DriverManager.getDriver("jdbc:postgresql://localhost/packaging_only").getClass().getName();
        if (!driver.equals("cloud.apocalipse.pz.internal.postgresql.Driver")) {
            throw new AssertionError("Relocated PostgreSQL JDBC service was not loaded");
        }
        Class.forName(PostgresConnections.class.getName()).getDeclaredConstructors();
        System.out.println("Packaged PostgreSQL, Hikari and JDBC service linkage passed; no database connection opened.");
    }
}
