package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.AgentModule;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Telemetry instrumentation registrations for game execution points. */
public final class TelemetryModule implements AgentModule {
    @Override
    public String name() {
        return "telemetry";
    }

    @Override
    public void initialize() {
        TelemetrySnapshotReporter.initialize();
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        return builder
            .type(named("zombie.Lua.Event"))
            .transform((dynamicBuilder, type, loader, module, domain) -> {
                System.out.println("[ApocBR-Agent] Applying Lua event timing telemetry hook.");
                return dynamicBuilder.visit(Advice.to(LuaEventTimingAdvice.class)
                    .on(named("trigger").and(takesArguments(3)).and(returns(boolean.class))));
            })
            .type(named("zombie.network.GameServer"))
            .transform((dynamicBuilder, type, loader, module, domain) -> {
                System.out.println("[ApocBR-Agent] Applying vanilla server-loop telemetry hook.");
                return dynamicBuilder.visit(new TelemetryServerLoopVisitor());
            });
    }
}
