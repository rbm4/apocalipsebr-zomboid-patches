package cloud.apocalipse.pz.agent.features.lua;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class LuaAdvice {
    public static class Expose {
        @Advice.OnMethodExit public static void exit() { LuaRuntime.exposed(); }
    }
    public static class Init {
        @Advice.OnMethodExit public static void exit() {
            LuaRuntime.luaInitialized();
            cloud.apocalipse.pz.agent.features.lua.entities.ApocBRModEntities.install();
        }
    }
    public static class Startup {
        @Advice.OnMethodEnter public static void enter() { LuaRuntime.start(); }
    }
    public static class Shutdown {
        @Advice.OnMethodEnter public static void enter() { LuaRuntime.shutdown(); }
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit() { LuaRuntime.cleanup(); }
    }
    public static class MainExit {
        @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit() { try { LuaRuntime.shutdown(); } finally { LuaRuntime.cleanup(); } }
    }
    public static class Update {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object source) { return LuaRuntime.beforeGeneratorUpdate(source); }
    }
    public static class Added {
        @Advice.OnMethodExit public static void exit(@Advice.This Object source) { LuaRuntime.generatorAdded(source); }
    }
    public static class Removed {
        @Advice.OnMethodExit public static void exit(@Advice.This Object source) { LuaRuntime.generatorRemoved(source); }
    }
    public static class Loaded {
        @Advice.OnMethodExit public static void exit(@Advice.This Object source) { LuaRuntime.generatorLoaded(source); }
    }
    public static class Saving {
        @Advice.OnMethodEnter public static void enter(@Advice.This Object source) { LuaRuntime.generatorSaving(source); }
    }
    public static class Change {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object source,@Advice.Origin("#m") String operation,
                @Advice.Argument(value=0,typing=Assigner.Typing.DYNAMIC) Object value) {
            return LuaRuntime.denyChange(source,operation,value);
        }
    }
    public static class Remove {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object source) { return LuaRuntime.denyRemoval(source); }
    }
    public static class RemoveFromMap {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object source) { return LuaRuntime.denyRemoval(source); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter boolean denied,@Advice.Return(readOnly=false) int result) {
            if (denied) result=-1;
        }
    }
    public static class Remote {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object source,@Advice.Argument(0) Object input) {
            return LuaRuntime.denyRemote(source,input);
        }
    }
    public static class Factory {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(0) Object cell,@Advice.Argument(1) java.nio.ByteBuffer input,
                @Advice.Return(readOnly=false,typing=Assigner.Typing.DYNAMIC) Object result) {
            result=LuaRuntime.powerBank(cell,input,result);
        }
    }
    public static class PlayerAccepted {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(1) Object connection) { LuaRuntime.playerAccepted(connection); }
    }
    private LuaAdvice() { }
}
