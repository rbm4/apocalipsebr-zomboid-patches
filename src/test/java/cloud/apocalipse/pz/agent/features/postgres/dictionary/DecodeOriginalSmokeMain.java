package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;

/** Isolated original-JAR entry points, with the real module installed before game classes load. */
public final class DecodeOriginalSmokeMain {
    public static void premain(String arguments, Instrumentation instrumentation) {
        var module = new DictionaryDecodeModule(); module.initialize();
        module.configure(new AgentBuilder.Default().disableClassFormatChanges()).installOn(instrumentation);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static DictionarySnapshot snapshot(String key) {
        return new DictionarySnapshot(List.of(new DictionarySnapshot.Registry("string", "Generic", 2, 64)),
            List.of(new DictionarySnapshot.Entry("string", "Generic", 1, "string", key, null, null, true,
                null, null, null, null, null, null, null, List.of())));
    }
    private static ByteBuffer bytes(DictionaryOrigin origin, int id) {
        var buffer = ByteBuffer.allocate(3).put((byte) 1).putShort((short) id).flip();
        PayloadProvenance.remember(buffer, new PayloadProvenance.Metadata(origin, 251)); return buffer;
    }
    public static void main(String[] args) throws Exception {
        var a = new DictionaryOrigin(1, UUID.randomUUID(), UUID.randomUUID());
        var b = new DictionaryOrigin(2, UUID.randomUUID(), UUID.randomUUID());
        DictionaryCache.install(a, snapshot("source-a")); DictionaryCache.install(b, snapshot("source-b"));
        var register = zombie.world.StringDictionary.Generic;
        check(register.getClass().getProtectionDomain().getCodeSource().getLocation().toString().endsWith("projectzomboid.jar"), "original-JAR register required");
        check("source-a".equals(register.loadString(bytes(a, 1), 251)), "native string reader must resolve source A");
        check("source-b".equals(register.loadString(bytes(b, 1), 251)), "same ID must resolve source B independently");
        check(!DictionaryDecode.active(), "scope must close after native return");
        try (var outer = DictionaryDecode.enter(bytes(a, 1))) {
            check("source-a".equals(register.loadString(ByteBuffer.wrap(new byte[]{1, 0, 1}), 251)), "untagged nested stream inherits parent");
            check("source-b".equals(register.loadString(bytes(b, 1), 251)), "explicit nested origin overrides parent");
            check("source-a".equals(register.get((short) 1)), "nested return restores parent");
            var unknown = bytes(null, 1);
            check(register.loadString(unknown, 251) == null, "explicit unknown origin selects vanilla, not parent");
        }
        try { register.loadString(bytes(a, 9), 251); throw new AssertionError("missing source ID must fail"); }
        catch (DictionaryDecode.Unavailable expected) { }
        check(!DictionaryDecode.active(), "failed native decoder must restore scope");
        try { register.loadString(bytes(new DictionaryOrigin(3, UUID.randomUUID(), UUID.randomUUID()), 1), 251); throw new AssertionError("unprepared dictionary must fail"); }
        catch (DictionaryDecode.Unavailable expected) { }
        check(!DictionaryDecode.active(), "entry failure must leave no scope");
        var mutable = bytes(a, 1); mutable.clear();
        check(PayloadProvenance.metadata(mutable).origin().equals(a), "metadata follows identity despite content/position changes");
        check(PayloadProvenance.metadata(mutable.duplicate()) == null, "copies require explicit propagation");
        var copy = mutable.duplicate(); PayloadProvenance.copy(mutable, copy);
        check(PayloadProvenance.metadata(copy).origin().equals(a), "explicit copy preserves origin");
        PayloadProvenance.remember(copy, null); check(PayloadProvenance.metadata(copy) == null, "reuse clears metadata");
        DictionaryRuntime.shutdown(); check(PayloadProvenance.metadata(mutable) == null, "shutdown clears metadata");
        System.out.println("original decoder entry points passed");
    }
}
