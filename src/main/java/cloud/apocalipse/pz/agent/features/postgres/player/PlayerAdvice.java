package cloud.apocalipse.pz.agent.features.postgres.player;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class PlayerAdvice {
    public static class Bootstrap {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.This Object database) { PlayerRuntime.bootstrap(database); return true; }
    }
    public static class Profile {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.This Object packet, @Advice.Argument(1) Object connection) { PlayerRuntime.profile(packet, connection); return true; }
    }
    public static class Create {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.This Object packet, @Advice.Argument(1) Object connection) { return PlayerRuntime.create(packet, connection); }
    }
    public static class ConnectPacket {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) Object input, @Advice.Argument(1) zombie.network.IConnection connection) {
            return PlayerRuntime.connect(input, connection, connection.getUserName(), true);
        }
    }
    public static class Connect {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) Object input, @Advice.Argument(1) Object connection, @Advice.Argument(2) String username) {
            return PlayerRuntime.connect(input, connection, username, false);
        }
    }
    public static class Loaded {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static Object enter(@Advice.Argument(0) int slot, @Advice.Argument(1) String identity) { return PlayerRuntime.loaded(slot, identity); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter Object loaded, @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) { result = loaded; }
    }
    public static class NativeSaved {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) Object data) { PlayerRuntime.nativeSave(data); return true; }
    }
    public static class Capture {
        @Advice.OnMethodExit public static void exit(@Advice.This Object data) { PlayerRuntime.nativeCaptured(data, true); }
    }
    public static class ClientCapture {
        @Advice.OnMethodExit public static void exit(@Advice.This Object data) { PlayerRuntime.nativeCaptured(data, false); }
    }
    public static class FinalSave {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(0) Object player) { PlayerRuntime.finalSave(player); }
    }
    public static class Drain {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { PlayerRuntime.drain(); return true; }
    }
    public static class Close {
        @Advice.OnMethodEnter public static void enter() { PlayerRuntime.closeDatabase(); }
    }
    public static class Main {
        @Advice.OnMethodEnter public static void enter() { PlayerRuntime.serverMain(); }
    }
    public static class Quit {
        @Advice.OnMethodEnter public static void enter() { PlayerRuntime.beginShutdown(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() { PlayerRuntime.endShutdown(); }
    }
    public static class Detached {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { return PlayerConstruction.active(); }
    }
    public static class Tick {
        @Advice.OnMethodEnter public static void enter() { PlayerMutations.beginTick(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() { PlayerMutations.endTick(); }
    }
    public static class Process {
        @Advice.OnMethodEnter public static void enter() { PlayerRuntime.beginProcess(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() { PlayerRuntime.endProcess(); }
    }
    public static class Convert {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter(@Advice.Argument(0) String user, @Advice.Argument(1) String steam) { PlayerRuntime.convertNative(user, steam); return true; }
    }
    private PlayerAdvice() { }
}
