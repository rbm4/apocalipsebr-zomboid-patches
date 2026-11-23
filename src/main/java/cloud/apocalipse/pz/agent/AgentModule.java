package cloud.apocalipse.pz.agent;

import net.bytebuddy.agent.builder.AgentBuilder;

/** A feature that contributes transformations to the shared JVM agent. */
public interface AgentModule {
    String name();

    /** Every module declares its installation contracts; new entries are discovered by integration tests. */
    java.util.List<HookCheck> hookChecks();

    /** Null when enabled; otherwise the reason its hooks are absent in this runtime configuration. */
    default String disabledReason() { return null; }

    default void initialize() {
    }

    AgentBuilder configure(AgentBuilder builder);
}
