package cloud.apocalipse.pz.agent;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;

/** Builds and installs the single transformation pipeline for this JVM. */
final class AgentRuntime {
    private AgentRuntime() {
    }

    static void install(Instrumentation instrumentation) {
        AgentBuilder builder = new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());

        for (AgentModule module : AgentModules.all()) {
            module.initialize();
            builder = module.configure(builder);
            System.out.println("[ApocBR-Agent] Registered feature module: " + module.name());
        }

        builder.installOn(instrumentation);
    }
}
