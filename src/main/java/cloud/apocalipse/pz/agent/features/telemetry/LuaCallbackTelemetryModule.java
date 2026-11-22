package cloud.apocalipse.pz.agent.features.telemetry;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.vm.Coroutine;
import se.krka.kahlua.vm.LuaClosure;
import static net.bytebuddy.matcher.ElementMatchers.*;

public final class LuaCallbackTelemetryModule implements AgentModule {
    private static final String EVENT = "zombie.Lua.Event";
    private static final String VM = "se.krka.kahlua.vm.KahluaThread";

    @Override public String name() { return "telemetry-lua-callbacks"; }

    @Override public String disabledReason() {
        return AgentConfiguration.booleanProperty(AgentConfiguration.TELEMETRY_LUA_PROPERTY)
            ? null : "Kahlua callback telemetry disabled by JVM property";
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = TickDiagnostics.class.getName();
        return List.of(
            new HookCheck("event-context", EVENT, "trigger", helper, "enterEvent", false),
            new HookCheck("vm-callback", VM, "call", helper, "recordCallback", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(EVENT)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(EventContext.class).on(named("trigger").and(hasDescriptor(
                    "(Lse/krka/kahlua/vm/KahluaTable;Lse/krka/kahlua/integration/LuaCaller;[Ljava/lang/Object;)Z")))))
            .type(named(VM)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(VmCall.class).on(named("call").and(hasDescriptor("(I)I")))));
    }

    public static final class EventContext {
        @Advice.OnMethodEnter
        public static String enter(@Advice.FieldValue("name") String event) {
            return TickDiagnostics.enterEvent(event);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter String previousEvent) {
            TickDiagnostics.exitEvent(previousEvent);
        }
    }

    public static final class VmCall {
        @Advice.OnMethodEnter(suppress = RuntimeException.class)
        public static long enter(@Advice.Argument(0) int arguments,
                @Advice.FieldValue("currentCoroutine") Coroutine coroutine,
                @Advice.Local("closure") LuaClosure closure,
                @Advice.Local("previousDepth") int previousDepth) {
            if (TickDiagnostics.canTimeCallback() && coroutine != null) {
                int index = coroutine.getTop() - arguments - 1;
                if (index >= 0 && index < coroutine.objectStack.length
                        && coroutine.objectStack[index] instanceof LuaClosure function) {
                    closure = function;
                    previousDepth = TickDiagnostics.enterCallback();
                    return System.nanoTime();
                }
            }
            return 0;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = RuntimeException.class)
        public static void exit(@Advice.Enter long started, @Advice.Local("closure") LuaClosure closure,
                @Advice.Local("previousDepth") int previousDepth) {
            if (closure != null) TickDiagnostics.recordCallback(closure, previousDepth, System.nanoTime() - started);
        }
    }
}
