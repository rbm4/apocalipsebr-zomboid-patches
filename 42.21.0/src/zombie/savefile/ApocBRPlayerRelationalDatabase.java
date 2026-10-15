package zombie.savefile;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Worker-only V5 JDBC codec. No engine objects, DDL, fallback or player byte writes. */
final class ApocBRPlayerRelationalDatabase {
    interface Connections { Connection open() throws SQLException; }
    record Candidate(UUID dataset,String label,String world,String identity,boolean coop,int slot,int nativeId,
                     int worldVersion,String blobHash,List<String> loadIssues) {
        Candidate { loadIssues=List.copyOf(loadIssues); }
        String key() { return dataset+":"+world+":"+(coop?"steam_coop":"username")+":"+identity+":"+slot; }
        UUID playerId() { return named("slot:"+key()); }
    }
    record Receipt(UUID character,UUID head,Map<Integer,UUID> aliases,boolean complete) {
        Receipt { aliases=Map.copyOf(aliases); }
    }
    private final Connections connections;
    ApocBRPlayerRelationalDatabase(Connections connections) { this.connections=connections; }
    static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    private static PreparedStatement statement(Connection connection,String sql) throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql); result.setQueryTimeout(2); return result;
    }
    Receipt inspect(Candidate candidate) throws SQLException {
        try(Connection connection=connections.open()) {
            UUID character=null,head=null; boolean complete=false;
            try(PreparedStatement query=statement(connection,"""
                    SELECT p.active_character_id,h.snapshot_id,s.status,s.schema_version,s.codec_version,s.source_dataset_id
                    FROM player_rel.player_slot p
                    LEFT JOIN player_rel.character_head h ON h.character_id=p.active_character_id
                    LEFT JOIN player_rel.snapshot s ON s.id=h.snapshot_id
                    WHERE p.world=? AND p.identity_mode=? AND p.identity_key=? AND p.player_index=?
                    """)) {
                query.setString(1,candidate.world); query.setString(2,candidate.coop?"steam_coop":"username"); query.setString(3,candidate.identity); query.setInt(4,candidate.slot);
                try(ResultSet rows=query.executeQuery()) { if(rows.next()) {
                    character=rows.getObject(1,UUID.class); head=rows.getObject(2,UUID.class);
                    UUID source=rows.getObject(6,UUID.class);
                    if(source!=null&&!candidate.dataset.equals(source))throw new SQLException("Player slot belongs to another source dataset","22023");
                    complete="complete".equals(rows.getString(3)) && rows.getInt(4)==ApocBRPlayerRelationalCapture.SCHEMA_VERSION
                            && rows.getInt(5)==ApocBRPlayerRelationalCapture.CODEC_VERSION && candidate.dataset.equals(rows.getObject(6,UUID.class));
                    if(rows.next()) throw new SQLException("Ambiguous relational player slot","21000");
                } }
            }
            if(character==null) character=named("character:"+candidate.key()+":1");
            Map<Integer,UUID> aliases=new HashMap<>();
            if(!complete) try(PreparedStatement query=statement(connection,"SELECT native_item_id,object_id FROM player_rel.source_item_alias WHERE source_dataset_id=? AND character_id=? AND retired_at IS NULL")) {
                query.setObject(1,candidate.dataset); query.setObject(2,character);
                try(ResultSet rows=query.executeQuery()) { while(rows.next()) if(aliases.put(rows.getInt(1),rows.getObject(2,UUID.class))!=null) throw new SQLException("Ambiguous item alias","21000"); }
            }
            connection.commit(); return new Receipt(character,head,aliases,complete);
        }
    }
    boolean persist(Candidate candidate,Receipt inspected,ApocBRPlayerRelationalCapture.Graph graph) throws SQLException {
        try(Connection connection=connections.open()) {
            try {
                execute(connection,"SET LOCAL lock_timeout = '250ms'");
                execute(connection,"SET CONSTRAINTS ALL DEFERRED");
                // Non-waiting per-slot lock. Does not lock public/native tables or other characters.
                try(PreparedStatement lock=statement(connection,"SELECT pg_try_advisory_xact_lock(?)")) {
                    lock.setLong(1,candidate.playerId().getMostSignificantBits());
                    try(ResultSet rows=lock.executeQuery()) { if(!rows.next()||!rows.getBoolean(1)) throw new SQLException("Backfill slot busy","55P03"); }
                }
                UUID currentCharacter=null,currentHead=null;
                try(PreparedStatement query=statement(connection,"SELECT p.active_character_id,h.snapshot_id FROM player_rel.player_slot p LEFT JOIN player_rel.character_head h ON h.character_id=p.active_character_id WHERE p.world=? AND p.identity_mode=? AND p.identity_key=? AND p.player_index=?")) {
                    query.setString(1,candidate.world);query.setString(2,candidate.coop?"steam_coop":"username");query.setString(3,candidate.identity);query.setInt(4,candidate.slot);
                    try(ResultSet rows=query.executeQuery()) { if(rows.next()) {currentCharacter=rows.getObject(1,UUID.class);currentHead=rows.getObject(2,UUID.class);} }
                }
                if((currentCharacter!=null&&!currentCharacter.equals(inspected.character)) || !Objects.equals(currentHead,inspected.head)) {connection.rollback();return false;}
                Instant now=Instant.now();
                insert(connection,"source_dataset",values("id",candidate.dataset,"label",candidate.label,"world",candidate.world,"dictionary_sha256",null,"game_build","42.21.0","mod_manifest_sha256",null,"created_at",now)," ON CONFLICT (id) DO NOTHING");
                insert(connection,"player_slot",values("id",candidate.playerId(),"legacy_networkplayer_id",candidate.nativeId,"world",candidate.world,"identity_mode",candidate.coop?"steam_coop":"username","identity_key",candidate.identity,"player_index",candidate.slot,"username",candidate.coop?null:candidate.identity,"steamid",candidate.coop?candidate.identity:null,"split_username",null,"active_character_id",null,"created_at",now)," ON CONFLICT (world,identity_mode,identity_key,player_index) DO NOTHING");
                UUID slotId;
                try(PreparedStatement query=statement(connection,"SELECT id FROM player_rel.player_slot WHERE world=? AND identity_mode=? AND identity_key=? AND player_index=?")) {
                    query.setString(1,candidate.world);query.setString(2,candidate.coop?"steam_coop":"username");query.setString(3,candidate.identity);query.setInt(4,candidate.slot);
                    try(ResultSet rows=query.executeQuery()){ if(!rows.next()) throw new SQLException("Missing player slot","P0001");slotId=rows.getObject(1,UUID.class); }
                }
                insert(connection,"character",values("id",graph.character(),"player_slot_id",slotId,"incarnation",1,"created_at",now,"retired_at",null,"retirement_reason",null)," ON CONFLICT (id) DO NOTHING");
                insert(connection,"snapshot",values("id",graph.snapshot(),"character_id",graph.character(),"source_dataset_id",candidate.dataset,"schema_version",ApocBRPlayerRelationalCapture.SCHEMA_VERSION,"codec_version",ApocBRPlayerRelationalCapture.CODEC_VERSION,"source_world_version",candidate.worldVersion,"source_blob_sha256",candidate.blobHash,"capture_revision",0L,"capture_reason","sqlite_load","captured_at",graph.capturedAt(),"status","staging","expected_object_count",graph.objects(),"expected_item_count",graph.items(),"expected_container_count",graph.containers(),"expected_edge_count",graph.edges(),"committed_at",null),"");
                long deadline=System.nanoTime()+15_000_000_000L;
                Map<String,List<ApocBRPlayerRelationalCapture.Row>> groups=new LinkedHashMap<>();
                for(var row:graph.rows()) groups.computeIfAbsent(row.table(),k->new ArrayList<>()).add(row);
                List<ApocBRPlayerRelationalCapture.Row> objects=groups.getOrDefault("object",List.of());
                List<Map<String,Object>> identities=new ArrayList<>();
                for(var row:objects) identities.add(values("id",row.values().get("id"),"kind",row.values().get("kind"),"created_at",now,"retired_at",null));
                insertBatch(connection,"object_identity",identities," ON CONFLICT (id) DO NOTHING",deadline);
                for(var group:groups.entrySet()) {
                    List<String> columns=ApocBRPlayerRelationalSchema.TABLES.get(group.getKey()).stream().map(ApocBRPlayerRelationalSchema.Column::name).toList();
                    try(PreparedStatement batch=statement(connection,sql(group.getKey(),columns))) {
                        int pending=0;
                        for(var row:group.getValue()) {
                            bind(batch,columns,row.values());batch.addBatch();pending++;
                            if(pending==128) { checkedBatch(batch,false);batch.clearBatch();pending=0; }
                            if(System.nanoTime()>deadline) throw new SQLException("Backfill transaction time budget","57014");
                        }
                        if(pending>0) checkedBatch(batch,false);
                    }
                }
                List<Map<String,Object>> aliases=new ArrayList<>();
                for(var item:groups.getOrDefault("item",List.of())) aliases.add(values("source_dataset_id",candidate.dataset,"character_id",graph.character(),"native_item_id",item.values().get("native_id"),"native_generation",1,"object_id",item.values().get("id"),"created_at",now,"retired_at",null));
                insertBatch(connection,"source_item_alias",aliases," ON CONFLICT (source_dataset_id,character_id,native_item_id,native_generation) DO NOTHING",deadline);
                for(String issue:graph.issues()) insert(connection,"sync_issue",values("snapshot_id",graph.snapshot(),"object_id",null,"code",issue,"source_path",null,"detail",null,"created_at",now),"");
                boolean complete=graph.issues().isEmpty();
                try(PreparedStatement update=statement(connection,"UPDATE player_rel.snapshot SET status=?,committed_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    update.setString(1,complete?"complete":"incomplete"); update.setObject(2,graph.snapshot()); update.executeUpdate();
                }
                if(complete) {
                    try(PreparedStatement update=statement(connection,"UPDATE player_rel.player_slot SET active_character_id=? WHERE id=? AND (active_character_id IS NULL OR active_character_id=?)")) {
                        update.setObject(1,graph.character());update.setObject(2,slotId);update.setObject(3,graph.character());
                        if(update.executeUpdate()!=1) throw new SQLException("Character changed during capture","40001");
                    }
                    try(PreparedStatement head=statement(connection,"INSERT INTO player_rel.character_head(character_id,snapshot_id,revision,published_at) VALUES(?,?,0,CURRENT_TIMESTAMP) ON CONFLICT(character_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id,revision=player_rel.character_head.revision+1,published_at=CURRENT_TIMESTAMP WHERE player_rel.character_head.snapshot_id=?")) {
                        head.setObject(1,graph.character());head.setObject(2,graph.snapshot());head.setObject(3,inspected.head);
                        if(head.executeUpdate()!=1) throw new SQLException("Stale character head","40001");
                    }
                }
                connection.commit();return true;
            } catch(SQLException|RuntimeException error) {
                try{connection.rollback();}catch(SQLException ignored){}
                throw error;
            }
        }
    }
    private static Map<String,Object> values(Object... pairs) {
        Map<String,Object> values=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;
    }
    private static String sql(String table,List<String> columns) {
        if(!ApocBRPlayerRelationalSchema.TABLES.containsKey(table))throw new IllegalArgumentException("Unknown table");
        return "INSERT INTO player_rel."+table+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
    }
    private static void bind(PreparedStatement statement,List<String> columns,Map<String,Object> values) throws SQLException {
        for(int index=0;index<columns.size();index++) {
            Object value=values.get(columns.get(index));
            statement.setObject(index+1,value instanceof Instant instant?Timestamp.from(instant):value);
        }
    }
    private static void insert(Connection connection,String table,Map<String,Object> values,String suffix) throws SQLException {
        List<String> columns=new ArrayList<>(values.keySet());
        try(PreparedStatement statement=statement(connection,sql(table,columns)+suffix)){bind(statement,columns,values);statement.executeUpdate();}
    }
    private static void insertBatch(Connection connection,String table,List<Map<String,Object>> rows,String suffix,long deadline)throws SQLException {
        if(rows.isEmpty())return;
        List<String> columns=new ArrayList<>(rows.getFirst().keySet());
        try(PreparedStatement batch=statement(connection,sql(table,columns)+suffix)) {
            int pending=0;
            for(var row:rows){bind(batch,columns,row);batch.addBatch();if(++pending==128){checkedBatch(batch,true);batch.clearBatch();pending=0;}if(System.nanoTime()>deadline)throw new SQLException("Backfill time budget","57014");}
            if(pending>0)checkedBatch(batch,true);
        }
    }
    private static void checkedBatch(PreparedStatement batch,boolean allowExisting)throws SQLException {
        for(int count:batch.executeBatch())if(count==Statement.EXECUTE_FAILED||(!allowExisting&&count!=1&&count!=Statement.SUCCESS_NO_INFO))throw new SQLException("Incomplete relational batch","P0001");
    }
    private static void execute(Connection connection,String sql) throws SQLException {try(PreparedStatement statement=statement(connection,sql)){statement.execute();}}
}
