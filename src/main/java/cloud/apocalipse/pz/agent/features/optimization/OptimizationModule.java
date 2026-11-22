package cloud.apocalipse.pz.agent.features.optimization;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;

/** Startup switches for independently reversible optimizations. */
public abstract class OptimizationModule implements AgentModule {
    private final String name;
    private final String property;

    protected OptimizationModule(String name, String property) {
        this.name = name;
        this.property = property;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String disabledReason() {
        return AgentConfiguration.optimizationEnabled(property) ? null : "Disabled by -D" + property;
    }
}
