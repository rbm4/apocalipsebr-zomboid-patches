package cloud.apocalipse.pz.agent.features.lua.login;
import net.bytebuddy.asm.Advice;
public final class AsyncAdvice {
    public static class Packet {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object packet,@Advice.Argument(0) Object kind,@Advice.Argument(1) Object connection) {
            return PacketRuntime.process(packet,kind,connection);
        }
    }
    public static class PacketLog {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object kind, @Advice.Argument(0) Object connection) {
            return PacketRuntime.omitLoginPacketLog(kind, connection);
        }
    }
    public static class Shutdown {
        @Advice.OnMethodEnter public static void enter(){AsyncRuntime.shutdown();}
    }
    public static class MainExit {
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){AsyncRuntime.shutdown();}
    }
}
