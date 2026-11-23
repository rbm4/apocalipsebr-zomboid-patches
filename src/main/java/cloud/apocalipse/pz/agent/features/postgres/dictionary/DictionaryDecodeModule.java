package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import cloud.apocalipse.pz.agent.*;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Source-aware native decoding, preserving original signatures and native execution. */
public final class DictionaryDecodeModule implements AgentModule {
    public static final List<String> TARGETS = List.of("zombie.world.WorldDictionary", "zombie.inventory.InventoryItem",
        "zombie.world.StringDictionary$StringRegister", "zombie.world.ScriptsDictionary$ScriptRegister", "zombie.iso.IsoObject");
    private boolean enabled;
    @Override public String name() { return "postgresql-dictionary-decode"; }
    @Override public String disabledReason() { return enabled ? null : "PostgreSQL dictionary decode disabled by configuration"; }
    @Override public void initialize() {
        enabled = AgentConfiguration.dictionaryDecodeEnabled();
        if (enabled) cloud.apocalipse.pz.agent.features.lua.HookPreflight.validate(TARGETS, hookChecks(), DictionaryDecodeModule::transform);
    }
    @Override public List<HookCheck> hookChecks() {
        var result = new ArrayList<HookCheck>();
        String decode = DictionaryDecode.class.getName();
        for (String method : List.of("getItemInfoFromID", "getEntityInfoFromID")) result.add(new HookCheck(method, TARGETS.getFirst(), method, decode, "info", false));
        for (String method : List.of("getItemTypeFromID", "getItemTypeDebugString", "getEntityTypeFromID", "getEntityTypeDebugString", "getSpriteNameFromID", "getObjectNameFromID"))
            result.add(new HookCheck(method, TARGETS.getFirst(), method, decode, "key", false));
        for (String method : List.of("loadItem", "load")) result.add(new HookCheck("item-" + method, TARGETS.get(1), method, decode, "enter", false));
        result.add(new HookCheck("item-nested-bytes", TARGETS.get(1), "load", decode, "inherit", false));
        result.add(new HookCheck("item-encode-origin", TARGETS.get(1), "storeInByteData", PayloadProvenance.class.getName(), "remember", false));
        for (int i = 2; i < 4; i++) {
            result.add(new HookCheck("register-" + i, TARGETS.get(i), "getInfoForID", decode, "registered", false));
            result.add(new HookCheck("register-value-" + i, TARGETS.get(i), "get", decode, "value", false));
            result.add(new HookCheck("register-load-" + i, TARGETS.get(i), i == 2 ? "loadString" : "loadScript", decode, "enter", false));
        }
        result.add(new HookCheck("object-load", TARGETS.get(4), "load", decode, "enter", false));
        result.add(new HookCheck("object-factory", TARGETS.get(4), "factoryFromFileInput", decode, "enter", false));
        return List.copyOf(result);
    }
    @Override public AgentBuilder configure(AgentBuilder builder) {
        return !enabled ? builder : builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic));
    }
    public static DynamicType.Builder<?> transform(String name, DynamicType.Builder<?> dynamic) {
        if (name.equals(TARGETS.getFirst())) return dynamic
            .visit(Advice.to(DecodeAdvice.WorldInfo.class).on(namedOneOf("getItemInfoFromID", "getEntityInfoFromID").and(takesArguments(short.class))))
            .visit(Advice.to(DecodeAdvice.WorldKey.class).on(namedOneOf("getItemTypeFromID", "getItemTypeDebugString", "getEntityTypeFromID", "getEntityTypeDebugString", "getSpriteNameFromID", "getObjectNameFromID").and(takesArguments(1))));
        if (name.equals(TARGETS.get(1))) return dynamic.visit(new ItemPayloadVisitor())
            .visit(Advice.to(DecodeAdvice.Scope.class).on(named("loadItem").and(takesArguments(4)).and(takesArgument(0, java.nio.ByteBuffer.class)).and(takesArgument(1, int.class)).and(takesArgument(2, boolean.class)).and(takesArgument(3, named("zombie.inventory.InventoryItem")))))
            .visit(Advice.to(DecodeAdvice.Scope.class).on(named("load").and(takesArguments(java.nio.ByteBuffer.class, int.class))))
            .visit(Advice.to(DecodeAdvice.Capture.class).on(named("storeInByteData").and(takesArguments(1))));
        if (name.equals(TARGETS.get(4))) return dynamic
            .visit(Advice.to(DecodeAdvice.Scope.class).on(named("load").and(takesArgument(0, java.nio.ByteBuffer.class))))
            .visit(Advice.to(DecodeAdvice.ObjectScope.class).on(named("factoryFromFileInput").and(takesArguments(2)).and(takesArgument(1, java.nio.ByteBuffer.class))));
        return dynamic.visit(Advice.to(DecodeAdvice.Register.class).on(named("getInfoForID").and(takesArguments(short.class))))
            .visit(Advice.to(DecodeAdvice.RegisterValue.class).on(named("get").and(takesArguments(short.class))))
            .visit(Advice.to(DecodeAdvice.Scope.class).on(namedOneOf("loadString", "loadScript").and(takesArguments(java.nio.ByteBuffer.class, int.class))));
    }
}
