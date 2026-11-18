package cloud.apocalipse.pz.agent;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import cloud.apocalipse.pz.workshop.ModUpdateRestartNotifier;
import cloud.apocalipse.pz.workshop.WorkshopUpdateAdvice;

/** Startup agent; transforms the class selected by the existing game classpath. */
public final class ApocBrAgent {
    private ApocBrAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        int javaVersion = ClassFileVersion.ofThisVm().getJavaVersion();
        ModUpdateRestartNotifier.initialize();
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(named("zombie.Lua.LuaManager$GlobalObject$ItemQueryJava"))
            .transform((builder, type, loader, module, domain) -> {
                System.out.println("[ApocBR-Agent] Applying Workshop update notification hook.");
                return builder.visit(Advice.to(WorkshopUpdateAdvice.class)
                    .on(named("inform").and(takesArguments(String.class)).and(returns(void.class))));
            })
            .installOn(instrumentation);
        System.out.println("[ApocBR-Agent] Loaded on Java " + javaVersion
            + "; Workshop update notification hook registered.");
    }
}
