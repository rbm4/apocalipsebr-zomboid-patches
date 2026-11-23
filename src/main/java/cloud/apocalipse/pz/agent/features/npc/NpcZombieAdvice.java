package cloud.apocalipse.pz.agent.features.npc;

import net.bytebuddy.asm.Advice;

public final class NpcZombieAdvice {
    public static class Auth {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) Object z){return NpcZombieHooks.auth(z);}
    }
    public static class Move {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) Object z,@Advice.Argument(1) Object owner){return NpcZombieHooks.move(z,owner);}
    }
    public static class Frame {
        @Advice.OnMethodEnter public static void enter(){NpcZombieHooks.beginFrame();}
        @Advice.OnMethodExit public static void exit(){NpcZombieHooks.endFrame();}
    }
    public static class Receive {
        @Advice.OnMethodExit public static void exit(@Advice.This Object packer,@Advice.Argument(0) Object z){NpcZombieHooks.received(packer,z);}
    }
    public static class Packet {
        @Advice.OnMethodExit public static void exit(@Advice.This Object packet,@Advice.Argument(0) Object z){NpcZombieHooks.prepare(packet,z);}
    }
    public static class Send {
        @Advice.OnMethodExit public static void exit(@Advice.This Object packer,@Advice.Argument(0) Object connection,@Advice.Argument(1) Object packet,@Advice.Return(readOnly=false) int count){count+=NpcZombieHooks.append(packer,connection,packet);}
    }
    public static class Hit {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object character){NpcZombieHooks.hit(character);}
    }
}
