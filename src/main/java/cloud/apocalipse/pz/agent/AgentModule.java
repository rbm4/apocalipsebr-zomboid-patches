package cloud.apocalipse.pz.agent;

import net.bytebuddy.agent.builder.AgentBuilder;

/** A feature that contributes transformations to the shared JVM agent. */
public interface AgentModule {
    String name();

    default void initialize() {
    }

    AgentBuilder configure(AgentBuilder builder);
}
