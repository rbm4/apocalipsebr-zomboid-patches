package cloud.apocalipse.pz.agent;

import cloud.apocalipse.pz.agent.features.telemetry.TelemetryModule;
import cloud.apocalipse.pz.agent.features.workshop.WorkshopModule;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;

/** Builds and installs the single transformation pipeline for this JVM. */
final class AgentRuntime {
    private static final List<AgentModule> MODULES = List.of(
        new WorkshopModule(),
        new TelemetryModule()
    );

    private AgentRuntime() {
    }

    static void install(Instrumentation instrumentation) {
        AgentBuilder builder = new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());

        for (AgentModule module : MODULES) {
            module.initialize();
            builder = module.configure(builder);
            System.out.println("[ApocBR-Agent] Registered feature module: " + module.name());
        }

        builder.installOn(instrumentation);
    }
}
