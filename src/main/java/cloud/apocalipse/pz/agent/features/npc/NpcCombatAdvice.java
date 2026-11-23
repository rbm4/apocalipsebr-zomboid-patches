package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.Advice;

public final class NpcCombatAdvice {
    public static class Parse {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object packet,@Advice.Argument(0) Object input){NpcCombatHooks.parsing(packet,input);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.This Object packet,@Advice.Argument(0) Object input,@Advice.Thrown Throwable failure){NpcCombatHooks.parsed(packet,input,failure);}
    }
    public static class Reset {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object packet){NpcCombatHooks.resetPacket(packet);}
    }
    public static class Envelope {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object packet,@Advice.Argument(1) Object connection){NpcCombatHooks.filter(packet,connection);}
    }
    public static class Process {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.This Object packet){return NpcCombatHooks.beforeProcess(packet);}
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.This Object packet){NpcCombatHooks.afterProcess(packet);}
    }
    public static class Target {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.This Object packet){return NpcCombatHooks.sanitize(packet);}
    }
    public static class Roll {
        @Advice.OnMethodExit public static void exit(@Advice.This Object hit,@Advice.Argument(1) Object target,@Advice.Return boolean success){NpcCombatHooks.rolled(hit,target,success);}
    }
    public static class Damage {
        @Advice.OnMethodEnter public static void enter(@Advice.FieldValue("parentChar") Object parent,@Advice.Argument(value=0,readOnly=false) float amount){amount=NpcCombatHooks.damage(parent,amount);}
    }
}
