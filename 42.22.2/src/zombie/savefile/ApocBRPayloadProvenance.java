package zombie.savefile;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.sql.*;
import java.util.*;

/** Out-of-band encoder identity. Never infer the origin of existing bytes from this JVM's ID. */
public record ApocBRPayloadProvenance(int serverId, UUID namespaceId, UUID viewId) implements java.io.Serializable {
    public ApocBRPayloadProvenance {
        if(serverId<1)throw new IllegalArgumentException("Server ID must be positive");
        Objects.requireNonNull(namespaceId);Objects.requireNonNull(viewId);
    }
    public static int configuredServerId() {
        String value=System.getProperty("apocbr.postgres.serverId");
        try {int id=Integer.parseInt(value);if(id>0)return id;}catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("Set apocbr.postgres.serverId to a registered positive player_rel.server.id");
    }
    private static volatile ApocBRPayloadProvenance local;
    public static void published(ApocBRPayloadProvenance origin) {
        if(origin.serverId()!=configuredServerId())throw new IllegalArgumentException("Published server ID mismatch");
        local=origin;
    }
    /** Capture at encoding, before handing bytes to another thread. No SQL or property lookup per item. */
    public static ApocBRPayloadProvenance localEncoding(){return local;}
    public static ApocBRPayloadProvenance read(ResultSet rows,int start)throws SQLException {
        Object server=rows.getObject(start),namespace=rows.getObject(start+1),view=rows.getObject(start+2);
        return of(server,namespace,view);
    }
    public static ApocBRPayloadProvenance of(Object server,Object namespace,Object view) {
        if(server==null&&namespace==null&&view==null)return null;
        if(server==null||namespace==null||view==null)throw new IllegalArgumentException("Incomplete payload provenance");
        return new ApocBRPayloadProvenance(((Number)server).intValue(),UUID.fromString(namespace.toString()),UUID.fromString(view.toString()));
    }
    public static void bind(PreparedStatement statement,int start,ApocBRPayloadProvenance origin)throws SQLException {
        statement.setObject(start,origin==null?null:origin.serverId(),Types.INTEGER);
        statement.setObject(start+1,origin==null?null:origin.namespaceId(),Types.OTHER);
        statement.setObject(start+2,origin==null?null:origin.viewId(),Types.OTHER);
    }
    public record Metadata(ApocBRPayloadProvenance origin,Integer worldVersion) implements java.io.Serializable {
        public Metadata {if(worldVersion!=null&&worldVersion<1)throw new IllegalArgumentException("Invalid payload version");}
    }
    // Native byteData has no extension fields or origin header. Track buffer/array identity weakly;
    // ByteBuffer.equals/hashCode compare mutable contents and cannot be used as map keys here.
    private static final ReferenceQueue<Object> collected=new ReferenceQueue<>();
    private static final Map<Identity,Metadata> payloads=new HashMap<>();
    private static final class Identity extends WeakReference<Object> {
        final int hash;
        Identity(Object object){super(object,collected);hash=System.identityHashCode(object);}
        public int hashCode(){return hash;}
        public boolean equals(Object other){return this==other||other instanceof Identity key&&get()!=null&&get()==key.get();}
    }
    private static void clean(){Identity key;while((key=(Identity)collected.poll())!=null)payloads.remove(key);}
    public static synchronized Metadata metadata(Object bytes){clean();return bytes==null?null:payloads.get(new Identity(bytes));}
    public static synchronized void remember(Object bytes,Metadata metadata) {
        clean();Objects.requireNonNull(bytes);Identity key=new Identity(bytes);
        if(metadata==null)payloads.remove(key);else payloads.put(key,metadata);
    }
    public static void copy(Object source,Object target){remember(target,metadata(source));}
}
