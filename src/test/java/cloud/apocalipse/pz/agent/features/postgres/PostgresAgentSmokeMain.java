package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.telemetry.TelemetryRegistry;
import java.nio.file.Path;
import java.util.UUID;
import zombie.network.GameServer;
import zombie.network.ServerWorldDatabase;

/** Exercises actual premain registration and shaded helper linkage with fake JDBC; no database connection. */
public final class PostgresAgentSmokeMain {
    public static void main(String[] arguments) throws Exception {
        System.setProperty(AgentConfiguration.POSTGRES_JOURNAL_DIRECTORY_PROPERTY,
            Path.of(System.getProperty("java.io.tmpdir"),"apocbr-hook-smoke-"+UUID.randomUUID()).toString());
        var database=new PostgresHookTest.Database();
        PostgresRuntime.installForTest(PostgresHookTest.SETTINGS,database.pool());
        var owner=new ServerWorldDatabase();
        owner.create(); owner.connect();
        if (!owner.containsUser("fixture")) throw new AssertionError("Native account body did not use the scoped database");
        database.failQuery=true;
        if (owner.authClient("fixture","hash","127.0.0.1",0L,1).authorized) {
            throw new AssertionError("Authentication ignored a caught SQL failure");
        }
        owner.close();
        if (database.shutdowns != 0) throw new AssertionError("Native close prematurely closed the shared pool");
        GameServer.main(new String[0]);
        if (TelemetryRegistry.snapshotTicksAndReset().calls()!=3 || TelemetryRegistry.snapshotLoopsAndReset().calls()!=5) {
            throw new AssertionError("PostgreSQL registration displaced server-loop telemetry");
        }
        if (database.shutdowns != 0) throw new AssertionError("Main exit prematurely closed the shared pool");
        var field = GameServer.class.getDeclaredField("shutdownHook");
        field.setAccessible(true);
        ((Thread) field.get(null)).run();
        if (!GameServer.done) throw new AssertionError("Vanilla shutdown body did not run");
        if (database.borrowed!=database.returned || database.shutdowns!=1) throw new AssertionError("Pool/lease lifecycle did not close");
        System.out.println("Registered PostgreSQL hooks, fail-closed authentication, telemetry coexistence and shutdown passed.");
    }
}
