package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import java.nio.ByteBuffer;
import java.util.*;
import zombie.world.WorldDictionary;

/** Historical lookup scopes. Decoders neither borrow JDBC connections nor wait on I/O. */
public final class DictionaryDecode {
    private static final ThreadLocal<Scope> CONTEXT = new ThreadLocal<>();
    public static final class Unavailable extends IllegalStateException {
        Unavailable(String message) { super(message); }
    }
    public static final class Scope implements AutoCloseable {
        final Scope previous;
        final PayloadProvenance.Metadata metadata;
        final DictionaryCache.View view;
        Unavailable failure;
        Scope(PayloadProvenance.Metadata metadata, DictionaryCache.View view) {
            previous = CONTEXT.get(); this.metadata = metadata; this.view = view; CONTEXT.set(this);
        }
        @Override public void close() {
            if (CONTEXT.get() != this) throw new IllegalStateException("Dictionary scope closed out of order");
            if (previous == null) CONTEXT.remove(); else CONTEXT.set(previous);
            // Native item loading catches Exceptions and would otherwise silently drop corrupt items.
            if (failure != null) throw failure;
        }
    }
    public static Scope enter(ByteBuffer bytes) {
        var metadata = PayloadProvenance.metadata(bytes);
        var parent = CONTEXT.get();
        if (metadata == null && parent != null) metadata = parent.metadata;
        DictionaryCache.View view = null;
        if (metadata != null && metadata.origin() != null) {
            try { view = DictionaryCache.ready(metadata.origin()); }
            catch (RuntimeException missing) { throw fail("Source dictionary is not prepared"); }
        }
        return new Scope(metadata, view);
    }
    public static boolean active() { var scope = CONTEXT.get(); return scope != null && scope.view != null; }
    public static void inherit(Object bytes, Integer version) {
        var scope = CONTEXT.get();
        // Clear stale provenance even for a native stream or an explicitly unknown source.
        PayloadProvenance.remember(bytes, scope == null || scope.metadata == null ? null
            : new PayloadProvenance.Metadata(scope.metadata.origin(), version));
    }
    static Unavailable fail(String message) {
        var error = new Unavailable(message);
        for (var scope = CONTEXT.get(); scope != null; scope = scope.previous) scope.failure = error;
        return error;
    }
    private static DictionarySnapshot.Entry entry(String family, String register, int id) {
        try { return CONTEXT.get().view.entry(family, register, id); }
        catch (RuntimeException missing) { throw fail("Source dictionary identity unavailable: " + family + "/" + register + "/" + id); }
    }
    public static String key(String family, int id) { return entry(family, "", id).key(); }
    public static Object info(String kind, short id) {
        try { return sourceInfo(kind, id); }
        catch (RuntimeException failure) { throw failure instanceof Unavailable unavailable ? unavailable : fail("Source definition cannot be decoded on this engine"); }
    }
    private static Object sourceInfo(String kind, short id) {
        var entry = entry("info", "", id);
        if (!entry.kind().equals(kind) || !entry.loaded() || Boolean.TRUE.equals(entry.removed()) || Boolean.TRUE.equals(entry.obsolete()))
            throw fail("Source definition is unavailable");
        Object nativeInfo = kind.equals("item") ? WorldDictionary.getItemInfoFromType(entry.key()) : WorldDictionary.getEntityInfoFromType(entry.key());
        if (nativeInfo == null || !(boolean) NativeDictionaryAccess.get(nativeInfo, "isLoaded")) throw fail("Destination definition is unavailable");
        Object info = NativeDictionaryAccess.create(kind.equals("item") ? "ItemInfo" : "EntityInfo");
        NativeDictionaryAccess.set(info, "registryId", (short) entry.id());
        NativeDictionaryAccess.set(info, "fullType", entry.key());
        NativeDictionaryAccess.set(info, "moduleName", entry.module());
        NativeDictionaryAccess.set(info, "name", entry.name());
        NativeDictionaryAccess.set(info, "isLoaded", entry.loaded());
        NativeDictionaryAccess.set(info, "modId", entry.mod());
        NativeDictionaryAccess.set(info, "obsolete", Boolean.TRUE.equals(entry.obsolete()));
        NativeDictionaryAccess.set(info, "removed", Boolean.TRUE.equals(entry.removed()));
        NativeDictionaryAccess.set(info, "existsAsVanilla", Boolean.TRUE.equals(entry.vanilla()));
        NativeDictionaryAccess.set(info, "isModded", Boolean.TRUE.equals(entry.modded()));
        NativeDictionaryAccess.set(info, "modOverrides", Boolean.TRUE.equals(entry.overridesPresent()) ? new ArrayList<>(entry.overrides()) : null);
        NativeDictionaryAccess.set(info, "entityScript", NativeDictionaryAccess.get(nativeInfo, "entityScript"));
        if (kind.equals("item")) NativeDictionaryAccess.set(info, "scriptItem", NativeDictionaryAccess.get(nativeInfo, "scriptItem"));
        return info;
    }
    public static Object registered(Object register, short id, boolean script) {
        try { return sourceRegistered(register, id, script); }
        catch (RuntimeException failure) { throw failure instanceof Unavailable unavailable ? unavailable : fail("Source register cannot be decoded on this engine"); }
    }
    private static Object sourceRegistered(Object register, short id, boolean script) {
        String name = (String) NativeDictionaryAccess.get(register, "name");
        var entry = entry(script ? "script" : "string", name, id);
        if (!script) {
            Object info = NativeDictionaryAccess.create("DictionaryStringInfo");
            NativeDictionaryAccess.set(info, "registryId", id);
            NativeDictionaryAccess.set(info, "string", entry.key());
            NativeDictionaryAccess.set(info, "isLoaded", entry.loaded()); return info;
        }
        Object nativeInfo = NativeDictionaryAccess.invoke(register, "getInfoForName", String.class, entry.key());
        if (nativeInfo == null || !entry.loaded() || !(boolean) NativeDictionaryAccess.get(nativeInfo, "isLoaded")
            || NativeDictionaryAccess.get(nativeInfo, "script") == null || !Objects.equals(entry.version(), NativeDictionaryAccess.get(nativeInfo, "version")))
            throw fail("Source script is unavailable or has a different version");
        Object info = NativeDictionaryAccess.create("DictionaryScriptInfo");
        for (String field : List.of("script", "name", "isLoaded", "version")) NativeDictionaryAccess.set(info, field, NativeDictionaryAccess.get(nativeInfo, field));
        NativeDictionaryAccess.set(info, "registryId", id); return info;
    }
    public static Object value(Object register, short id, boolean script) {
        try { return NativeDictionaryAccess.get(registered(register, id, script), script ? "script" : "string"); }
        catch (RuntimeException failure) { throw failure instanceof Unavailable unavailable ? unavailable : fail("Source register value cannot be decoded"); }
    }
    public static void failed(Throwable failure) { if (failure != null && active()) fail("Historical native payload failed to decode"); }
    private DictionaryDecode() { }
}
