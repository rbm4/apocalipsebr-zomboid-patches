package zombie.world;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import zombie.savefile.ApocBRPayloadProvenance;
import zombie.savefile.ApocBRWorldDictionaryDatabase;

/** Exact, immutable source views. JDBC preparation belongs to an I/O worker, never a decoder. */
public final class ApocBRWorldDictionaryDecode {
    private static final ConcurrentHashMap<ApocBRPayloadProvenance,CompletableFuture<View>> views=new ConcurrentHashMap<>();
    private static int entries;
    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message){super(message);}
    }
    public static final class View {
        final Map<String,ApocBRWorldDictionarySnapshot.Entry> ids;
        View(ApocBRWorldDictionarySnapshot snapshot) {
            Map<String,ApocBRWorldDictionarySnapshot.Entry> result=new HashMap<>();
            for(var entry:snapshot.entries())result.put(entry.identity(),entry);
            ids=Map.copyOf(result);
        }
        ApocBRWorldDictionarySnapshot.Entry entry(String family,String register,int id) {
            var entry=ids.get(family+":"+register+":"+id);
            if(entry==null)throw fail("Source dictionary has no "+family+"/"+register+" ID "+id);
            return entry;
        }
    }
    private static synchronized void reserve(ApocBRWorldDictionarySnapshot snapshot) {
        if(entries+snapshot.entries().size()>1_000_000)throw new Unavailable("Dictionary cache entry budget exceeded");
        entries+=snapshot.entries().size();
    }
    private static void complete(CompletableFuture<View> future,ApocBRWorldDictionarySnapshot snapshot) {
        synchronized(future) {
            if(future.isDone())return;
            View view=new View(snapshot);reserve(snapshot);future.complete(view);
        }
    }
    public static void install(ApocBRPayloadProvenance origin,ApocBRWorldDictionarySnapshot snapshot) {
        var future=new CompletableFuture<View>();
        var existing=views.putIfAbsent(origin,future);
        if(existing!=null){complete(existing,snapshot);return;}
        try {if(views.size()>256)throw new Unavailable("Dictionary cache view budget exceeded");
            complete(future,snapshot);
        }catch(RuntimeException failure){future.completeExceptionally(failure);views.remove(origin,future);throw failure;}
    }
    public static void prepare(ApocBRPayloadProvenance origin)throws SQLException {
        if(origin==null)return;
        var created=new CompletableFuture<View>();var future=views.putIfAbsent(origin,created);
        if(future==null) {
            future=created;
            try {
                if(views.size()>256)throw new Unavailable("Dictionary cache view budget exceeded");
                var snapshot=new ApocBRWorldDictionaryDatabase().load(origin);complete(created,snapshot);
            }catch(SQLException|RuntimeException failure){if(created.completeExceptionally(failure))views.remove(origin,created);}
        }
        try{future.join();}catch(CompletionException failure){throw new SQLException("Source dictionary preparation failed","55000");}
    }
    public static CompletableFuture<Void> prepareAsync(Collection<ApocBRPayloadProvenance> origins,Executor io) {
        return CompletableFuture.runAsync(()->{try{for(var origin:new LinkedHashSet<>(origins))prepare(origin);}catch(SQLException error){throw new CompletionException(error);}},io);
    }
    private static View ready(ApocBRPayloadProvenance origin) {
        var future=views.get(origin);
        if(future==null||!future.isDone()||future.isCompletedExceptionally())throw fail("Source dictionary is not prepared: server="+origin.serverId()+"; view="+origin.viewId());
        return future.getNow(null);
    }
    private static final ThreadLocal<Scope> context=new ThreadLocal<>();
    public static final class Scope implements AutoCloseable {
        final Scope previous;final ApocBRPayloadProvenance origin;final View view;Unavailable failure;
        Scope(ApocBRPayloadProvenance origin,View view){previous=context.get();this.origin=origin;this.view=view;context.set(this);}
        public void close(){context.set(previous);if(previous==null)context.remove();if(failure!=null)throw new Unavailable(failure.getMessage());}
    }
    /** Nested native buffers inherit their containing stream; explicit unknown metadata selects native. */
    public static Scope enter(ByteBuffer bytes) {
        var metadata=ApocBRPayloadProvenance.metadata(bytes);var parent=context.get();
        var origin=metadata!=null?metadata.origin():parent==null?null:parent.origin;
        return new Scope(origin,origin==null?null:ready(origin));
    }
    public static View current(){var scope=context.get();return scope==null?null:scope.view;}
    public static void inherit(Object bytes,Integer version) {
        var scope=context.get();
        if(scope!=null&&scope.origin!=null)ApocBRPayloadProvenance.remember(bytes,new ApocBRPayloadProvenance.Metadata(scope.origin,version));
    }
    static Unavailable fail(String message) {
        var error=new Unavailable(message);
        for(var scope=context.get();scope!=null;scope=scope.previous)scope.failure=error;
        return error;
    }
    static String key(String family,String register,int id){return current().entry(family,register,id).key();}
    static ItemInfo item(short id) {
        var entry=current().entry("info","",id);
        if(!entry.kind().equals("item"))throw fail("Source info ID is not an item");
        var nativeInfo=WorldDictionary.getItemInfoFromType(entry.key());
        if(nativeInfo==null||!nativeInfo.isValid())throw fail("Source item definition is unavailable on this engine: "+entry.key());
        ItemInfo info=new ItemInfo();fill(info,entry);info.scriptItem=nativeInfo.scriptItem;info.entityScript=nativeInfo.entityScript;return info;
    }
    static EntityInfo entity(short id) {
        var entry=current().entry("info","",id);
        if(!entry.kind().equals("entity"))throw fail("Source info ID is not an entity");
        var nativeInfo=WorldDictionary.getEntityInfoFromType(entry.key());
        if(nativeInfo==null||!nativeInfo.isValid())throw fail("Source entity definition is unavailable on this engine: "+entry.key());
        EntityInfo info=new EntityInfo();fill(info,entry);info.entityScript=nativeInfo.entityScript;return info;
    }
    private static void fill(DictionaryInfo<?> info,ApocBRWorldDictionarySnapshot.Entry entry) {
        info.registryId=(short)entry.id();info.fullType=entry.key();info.moduleName=entry.module();info.name=entry.name();
        info.isLoaded=entry.loaded();info.modId=entry.mod();info.obsolete=Boolean.TRUE.equals(entry.obsolete());info.removed=Boolean.TRUE.equals(entry.removed());
        info.existsAsVanilla=Boolean.TRUE.equals(entry.vanilla());info.isModded=Boolean.TRUE.equals(entry.modded());
        info.modOverrides=Boolean.TRUE.equals(entry.overridesPresent())?new ArrayList<>(entry.overrides()):null;
        if(!info.isValid())throw fail("Source definition is not loaded: "+entry.key());
    }
    static DictionaryStringInfo string(String register,short id) {
        var entry=current().entry("string",register,id);
        DictionaryStringInfo info=new DictionaryStringInfo();info.registryId=id;info.string=entry.key();info.isLoaded=entry.loaded();return info;
    }
    static <T extends zombie.scripting.objects.BaseScriptObject> DictionaryScriptInfo<T> script(ScriptsDictionary.ScriptRegister<T> register,String name,short id) {
        var entry=current().entry("script",name,id);var nativeInfo=register.getInfoForName(entry.key());
        if(nativeInfo==null||nativeInfo.script==null||!nativeInfo.isLoaded||!entry.loaded()||!Objects.equals(entry.version(),nativeInfo.version))
            throw fail("Source script is missing or has a different version: "+entry.key());
        var info=nativeInfo.copy();info.registryId=id;return info;
    }
    private ApocBRWorldDictionaryDecode(){}
}
