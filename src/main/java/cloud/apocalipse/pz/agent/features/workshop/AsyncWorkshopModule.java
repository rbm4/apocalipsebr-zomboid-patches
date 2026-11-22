package cloud.apocalipse.pz.agent.features.workshop;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class AsyncWorkshopModule implements AgentModule {
    private static final String GLOBAL = "zombie.Lua.LuaManager$GlobalObject";
    private static final String STEAM = "zombie.core.znet.SteamUtils";
    private static final String SHUTDOWN = "zombie.network.GameServer$1";

    @Override public String name() { return "workshop-async-check"; }

    @Override public String disabledReason() {
        return AgentConfiguration.booleanProperty(AgentConfiguration.WORKSHOP_ASYNC_CHECK_PROPERTY)
            ? null : "asynchronous Workshop checking disabled by JVM property";
    }

    @Override public List<HookCheck> hookChecks() {
        String runtime = AsyncWorkshopCheck.class.getName();
        return List.of(
            new HookCheck("enqueue", GLOBAL, "checkModsNeedUpdate", runtime, "enqueue", false),
            new HookCheck("advance", STEAM, "runLoop", runtime, "afterSteamLoop", false),
            new HookCheck("shutdown", SHUTDOWN, "run", runtime, "shutdown", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(GLOBAL)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Enqueue.class).on(named("checkModsNeedUpdate").and(isStatic())
                    .and(hasDescriptor("(Lzombie/core/raknet/UdpConnection;)V")))))
            .type(named(STEAM)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Pump.class).on(named("runLoop").and(isStatic())
                    .and(hasDescriptor("()V")))))
            .type(named(SHUTDOWN)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(Shutdown.class).on(named("run").and(hasDescriptor("()V")))));
    }

    public static final class Enqueue {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object connection) {
            return AsyncWorkshopCheck.enqueue(connection);
        }
    }

    public static final class Pump {
        @Advice.OnMethodEnter
        public static boolean enter() {
            return AsyncWorkshopCheck.beforeSteamLoop();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean owner, @Advice.Thrown Throwable failure) {
            if (owner) AsyncWorkshopCheck.afterSteamLoop(failure == null);
        }
    }

    public static final class Shutdown {
        @Advice.OnMethodEnter
        public static void enter() {
            AsyncWorkshopCheck.shutdown();
        }
    }
}
