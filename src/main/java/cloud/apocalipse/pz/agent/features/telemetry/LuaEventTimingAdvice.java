package cloud.apocalipse.pz.agent.features.telemetry;

import java.util.List;
import net.bytebuddy.asm.Advice;

/** Measures total time spent in the Lua event trigger method. */
public final class LuaEventTimingAdvice {
    private LuaEventTimingAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter(
            @Advice.FieldValue("name") String name,
            @Advice.FieldValue("callbacks") List<?> callbacks,
            @Advice.Local("startedNanos") long startedNanos,
            @Advice.Local("eventName") String eventName,
            @Advice.Local("callbackCount") int callbackCount) {
        startedNanos = System.nanoTime();
        eventName = name;
        callbackCount = callbacks.size();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
            @Advice.Local("startedNanos") long startedNanos,
            @Advice.Local("eventName") String eventName,
            @Advice.Local("callbackCount") int callbackCount) {
        TelemetryRegistry.recordLuaEvent(eventName, callbackCount, System.nanoTime() - startedNanos);
    }
}
