package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.features.postgres.PostgresModule;
import cloud.apocalipse.pz.agent.features.telemetry.TelemetryModule;
import cloud.apocalipse.pz.agent.features.workshop.WorkshopModule;
import java.util.List;

/** Single module registry shared by runtime installation and read-only hook diagnostics. */
public final class AgentModules {
    private static final List<AgentModule> MODULES = List.of(
        new WorkshopModule(), new TelemetryModule(), new PostgresModule());

    private AgentModules() { }

    public static List<AgentModule> all() { return MODULES; }
}
