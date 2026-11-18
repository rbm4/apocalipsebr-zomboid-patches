package cloud.apocalipse.pz.agent.features.workshop;

import cloud.apocalipse.pz.agent.AgentModule;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Workshop update notification instrumentation and lifecycle. */
public final class WorkshopModule implements AgentModule {
    @Override
    public String name() {
        return "workshop";
    }

    @Override
    public void initialize() {
        ModUpdateRestartNotifier.initialize();
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        return builder
            .type(named("zombie.Lua.LuaManager$GlobalObject$ItemQueryJava"))
            .transform((dynamicBuilder, type, loader, module, domain) -> {
                System.out.println("[ApocBR-Agent] Applying Workshop update notification hook.");
                return dynamicBuilder.visit(Advice.to(WorkshopUpdateAdvice.class)
                    .on(named("inform").and(takesArguments(String.class)).and(returns(void.class))));
            });
    }
}
