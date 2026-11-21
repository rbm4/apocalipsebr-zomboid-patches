package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.nio.ByteBuffer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class DecodeAdvice {
    public static class Scope {
        @Advice.OnMethodEnter public static DictionaryDecode.Scope enter(@Advice.Argument(0) ByteBuffer input) { return DictionaryDecode.enter(input); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Enter DictionaryDecode.Scope scope, @Advice.Thrown Throwable failure) { DictionaryDecode.failed(failure); scope.close(); }
    }
    public static class ObjectScope {
        @Advice.OnMethodEnter public static DictionaryDecode.Scope enter(@Advice.Argument(1) ByteBuffer input) { return DictionaryDecode.enter(input); }
        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit(@Advice.Enter DictionaryDecode.Scope scope, @Advice.Thrown Throwable failure) { DictionaryDecode.failed(failure); scope.close(); }
    }
    public static class Capture {
        @Advice.OnMethodEnter public static DictionaryOrigin enter() { return DictionaryRuntime.localEncoding(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter DictionaryOrigin origin, @Advice.FieldValue("byteData") ByteBuffer bytes) {
            if (bytes != null) PayloadProvenance.remember(bytes, new PayloadProvenance.Metadata(origin, zombie.iso.IsoWorld.getWorldVersion()));
        }
    }
    public static class WorldInfo {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { return DictionaryDecode.active(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter boolean historical, @Advice.Argument(0) short id,
                @Advice.Origin("#m") String method, @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
            if (historical) result = DictionaryDecode.info(method.contains("Entity") ? "entity" : "item", id);
        }
    }
    public static class WorldKey {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { return DictionaryDecode.active(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter boolean historical,
                @Advice.AllArguments Object[] args, @Advice.Origin("#m") String method, @Advice.Return(readOnly = false) String result) {
            if (historical) result = DictionaryDecode.key(method.contains("Sprite") ? "sprite" : method.contains("Object") ? "object" : "info", ((Number) args[0]).intValue());
        }
    }
    public static class Register {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { return DictionaryDecode.active(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter boolean historical, @Advice.This Object register,
                @Advice.Argument(0) short id, @Advice.Origin("#t") String type,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
            if (historical) result = DictionaryDecode.registered(register, id, type.contains("ScriptsDictionary"));
        }
    }
    public static class RegisterValue {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class) public static boolean enter() { return DictionaryDecode.active(); }
        @Advice.OnMethodExit public static void exit(@Advice.Enter boolean historical, @Advice.This Object register,
                @Advice.Argument(0) short id, @Advice.Origin("#t") String type,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
            if (historical) result = DictionaryDecode.value(register, id, type.contains("ScriptsDictionary"));
        }
    }
    private DecodeAdvice() { }
}
