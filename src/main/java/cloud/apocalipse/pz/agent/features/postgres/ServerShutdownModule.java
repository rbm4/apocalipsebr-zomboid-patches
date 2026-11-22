package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.HookPreflight;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Runs shared cleanup on the vanilla JVM shutdown thread after its last save. */
public final class ServerShutdownModule implements AgentModule {
    private static final String TARGET = "zombie.network.GameServer$1";
    private boolean enabled;

    @Override public String name() { return "server-shutdown"; }

    @Override public void initialize() {
        enabled = AgentConfiguration.postgresSettings().enabled() || AgentConfiguration.npcSettings().enabled()
            || AgentConfiguration.luaEnabled();
        if (enabled) HookPreflight.validate(List.of(TARGET), hookChecks(), (name, builder) -> transform(builder));
    }

    @Override public String disabledReason() {
        return enabled ? null : "PostgreSQL, NPC and server Lua are disabled";
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("shared-shutdown", TARGET, "run",
            PostgresRuntime.class.getName(), "shutdownAfter", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        return enabled ? builder.type(named(TARGET))
            .transform((dynamic, type, loader, module, domain) -> transform(dynamic)) : builder;
    }

    static DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
        return builder.visit(Advice.to(PostgresAdvice.Shutdown.class)
            .on(named("run").and(not(isStatic())).and(takesArguments(0)).and(returns(void.class))));
    }
}
