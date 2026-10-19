package zombie.savefile;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import zombie.debug.DebugLog;

/** Bounded reconstruction diagnostics. Never logs identities, SQL, row values or opaque bytes. */
final class ApocBRPlayerReconstructionLog {
    private static final ThreadLocal<Attempt> current=new ThreadLocal<>();
    private static java.util.function.Consumer<String> sink=DebugLog::log;
    private static final Set<String> REASONS=Set.of("Relational login requires a complete compatible head","Relational login graph count mismatch",
        "Relational login edge count mismatch","Relational login read exceeds graph budget","Missing root inventory","Duplicate item","Duplicate container",
        "Invalid container owner","Invalid saved item type or identity","Saved item type changed","Incomplete component","Unsupported saved component",
        "Missing relational reference","Unresolved relational reference","Duplicate relational identity","Unresolved structured reference","Cyclic structured value");
    static Attempt begin(String phase,int slot){return new Attempt(phase,slot);}
    static void table(String table,int count){var a=current.get();if(a!=null){a.table=token(table);a.rows=count;a.field="NONE";}}
    static void field(String field){var a=current.get();if(a!=null)a.field=token(field);}
    static void subject(String kind,Object id,Object type){var a=current.get();if(a!=null)a.subject=token(kind)+":"+(id instanceof Number?id:"NONE")+":"+token(type);}
    static String token(Object value){if(value==null)return "NONE";String text=value.toString().replaceAll("[^A-Za-z0-9_.$:/-]","_");return text.substring(0,Math.min(160,text.length()));}
    static final class Attempt implements AutoCloseable {
        final Attempt previous;final UUID request;final String phase;final int slot;final long started=System.nanoTime();
        String stage="START",table="NONE",field="NONE",subject="NONE";int rows;boolean failed;
        Attempt(String phase,int slot){previous=current.get();request=previous==null?UUID.randomUUID():previous.request;this.phase=phase;this.slot=slot;current.set(this);event("BEGIN","");}
        void lookup(String world,String identity,boolean coop){event("LOOKUP","; world="+token(world)+"; identityMode="+(coop?"steam_coop":"username")
            +"; identityRef="+UUID.nameUUIDFromBytes(String.valueOf(identity).getBytes(StandardCharsets.UTF_8)));}
        void stage(String stage){this.stage=stage;table="NONE";field="NONE";subject="NONE";rows=0;event("STAGE","");}
        void event(String event,String detail){sink.accept("[ApocBR][Reconstruction] "+event+"; request="+request+"; phase="+phase+"; slot="+slot
            +"; stage="+stage+"; elapsedMs="+(System.nanoTime()-started)/1_000_000L+detail);}
        void profile(ApocBRPlayerRelationalReader.Profile profile){
            if(profile==null){event("NO_PROFILE","");return;}
            var metadata=profile.rows("snapshot");if(!metadata.isEmpty()){
                var m=metadata.getFirst();event("HEAD","; snapshot="+token(m.get("snapshot_id"))+"; character="+token(m.get("persisted_character_id"))
                    +"; dataset="+token(m.get("source_dataset_id"))+"; status="+token(m.get("status"))+"; schema="+token(m.get("schema_version"))
                    +"; codec="+token(m.get("codec_version"))+"; worldVersion="+token(m.get("source_world_version")));}
            event("GRAPH","; tables="+profile.tables().size()+"; objects="+profile.rows("object").size()+"; items="+profile.rows("item").size()
                +"; containers="+profile.rows("container").size()+"; components="+profile.rows("component").size()+"; values="+profile.rows("value_node").size());
        }
        void failure(Throwable failure){
            if(failed)return;failed=true;
            StringBuilder detail=new StringBuilder("; table="+table+"; rows="+rows+"; field="+field+"; subject="+subject);
            Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());int depth=0;
            for(Throwable cause=failure;cause!=null&&seen.add(cause)&&depth++<4;cause=cause.getCause()){
                detail.append("; exception=").append(cause.getClass().getName());
                if(REASONS.contains(cause.getMessage()==null?"":cause.getMessage()))detail.append("; reason=").append(cause.getMessage());
                if(cause instanceof SQLException sql)detail.append("; SQLState=").append(token(sql.getSQLState()));
                detail.append("; frames=");var frames=cause.getStackTrace();
                for(int i=0;i<Math.min(6,frames.length);i++){if(i>0)detail.append('|');detail.append(frames[i].getClassName()).append('.').append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());}
            }
            event("FAILED",detail.toString());
        }
        public void close(){current.set(previous);if(previous==null)current.remove();}
    }
    private ApocBRPlayerReconstructionLog(){}
}
