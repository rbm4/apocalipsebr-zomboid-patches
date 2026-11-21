package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Telemetry instrumentation registrations for game execution points. */
public final class TelemetryModule implements AgentModule {
    @Override public List<HookCheck> hookChecks() {
        String loop = "cloud.apocalipse.pz.agent.features.telemetry.TelemetryServerLoopMetrics";
        return List.of(
            new HookCheck("loop-start", "zombie.network.GameServer", "main", loop, "beginIteration", false),
            new HookCheck("tick", "zombie.network.GameServer", "main", loop, "recordVanillaTick", false),
            new HookCheck("loop-end", "zombie.network.GameServer", "main", loop, "finishVanillaIteration", false),
            new HookCheck("lua-event", "zombie.Lua.Event", "trigger",
                "cloud.apocalipse.pz.agent.features.telemetry.TelemetryRegistry", "recordLuaEvent", false));
    }

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
