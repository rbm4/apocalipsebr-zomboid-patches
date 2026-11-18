package cloud.apocalipse.pz.agent.features.telemetry;

import net.bytebuddy.asm.Advice;

/** Measures total time spent in the Lua event trigger method. */
public final class LuaEventTimingAdvice {
    private LuaEventTimingAdvice() {
    }

    @Advice.OnMethodEnter
    public static long enter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Enter long startedNanos) {
        TelemetryRegistry.recordLuaEventTrigger(System.nanoTime() - startedNanos);
    }
}
