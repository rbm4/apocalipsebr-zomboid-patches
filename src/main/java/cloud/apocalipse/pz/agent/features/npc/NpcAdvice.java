package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.Advice;

public final class NpcAdvice {
    public static final class Quit {
        @Advice.OnMethodEnter public static void enter() { NpcRuntime.shutdown(); }
    }
    public static final class MainExit {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() { NpcRuntime.shutdown(); }
    }
}
