package zombie.savefile;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** JDBC-only login read. Its detached result may cross from an I/O executor to the game thread. */
final class ApocBRPlayerRelationalReader {
    private final ApocBRPlayerRelationalDatabase.Connections connections;
    ApocBRPlayerRelationalReader() { this(ApocBRPostgresConnector::borrowPlayerConnection); }
    ApocBRPlayerRelationalReader(ApocBRPlayerRelationalDatabase.Connections connections) { this.connections=connections; }
    record Profile(Map<String,List<Map<String,Object>>> tables) {
        Profile {
            Map<String,List<Map<String,Object>>> copy=new LinkedHashMap<>();
            tables.forEach((k,v)->copy.put(k,v.stream().map(r->Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList()));
            tables=Collections.unmodifiableMap(copy);
        }
        List<Map<String,Object>> rows(String table) { return tables.getOrDefault(table,List.of()); }
        Map<String,Object> one(String table) {
            var rows=rows(table);
            if(rows.size()!=1)throw new IllegalStateException("Expected one relational row: "+table);
            return rows.getFirst();
        }
    }
    // One statement gives every table the same MVCC view, including a head being updated in place.
    // Graph UUIDs remain on the server; dictionary provenance UUIDs identify external immutable views.
    static final String SQL=sql();
    /** Supply a bounded JDBC executor. Complete engine reconstruction later on the simulation thread. */
    CompletableFuture<Profile> loadAsync(String world,String identity,int slot,boolean coop,Executor executor) {
        return CompletableFuture.supplyAsync(()->{
            try{return load(world,identity,slot,coop);}
            catch(SQLException error){throw new CompletionException(error);}
        },executor);
    }
    static String sql() {
        String prefix="""
                WITH selected AS MATERIALIZED (
                  SELECT h.snapshot_id,s.*,p.split_username AS profile_split_username FROM player_rel.player_slot p
                  JOIN player_rel.character_head h ON h.character_id=p.active_character_id
                  JOIN player_rel.snapshot s ON s.id=h.snapshot_id
                  WHERE p.world=? AND p.identity_mode=? AND p.identity_key=? AND p.player_index=?
                ), refs AS MATERIALIZED (
                  SELECT id,row_number() OVER (ORDER BY id)::integer AS ordinal FROM (
                    SELECT o.id FROM player_rel.object o JOIN selected s ON s.snapshot_id=o.snapshot_id
                    UNION SELECT n.id FROM player_rel.value_node n JOIN selected s ON s.snapshot_id=n.snapshot_id
                  ) identities
                )
                SELECT 'snapshot' AS table_name,jsonb_build_object(
                  'status',s.status,'schema_version',s.schema_version,'codec_version',s.codec_version,
                  'source_world_version',s.source_world_version,'split_username',s.profile_split_username,'expected_object_count',s.expected_object_count,
                  'expected_item_count',s.expected_item_count,'expected_container_count',s.expected_container_count,
                  'expected_edge_count',s.expected_edge_count) AS payload FROM selected s
                """;
        StringBuilder sql=new StringBuilder(prefix);
        for(var table:ApocBRPlayerRelationalSchema.TABLES.entrySet()) {
            if(table.getValue().stream().noneMatch(c->c.name().equals("snapshot_id"))||table.getKey().equals("character_head")||table.getKey().equals("sync_issue"))continue;
            List<String> fields=new ArrayList<>();
            StringBuilder joins=new StringBuilder();int reference=0;
            for(var column:table.getValue()) {
                if(column.name().equals("snapshot_id"))continue;
                String value="t."+column.name();
                if(column.type().equals("uuid")&&!column.name().startsWith("source_dictionary_")) {
                    String alias="r"+reference++;
                    joins.append(" LEFT JOIN refs ").append(alias).append(" ON ").append(alias).append(".id=").append(value);
                    value=alias+".ordinal";
                }
                if(column.type().equals("bytea"))value="encode("+value+",'hex')";
                fields.add("'"+column.name()+"',"+value);
            }
            sql.append(" UNION ALL SELECT '").append(table.getKey()).append("',jsonb_build_object(")
                    .append(String.join(",",fields)).append(") FROM player_rel.").append(table.getKey())
                    .append(" t JOIN selected s ON s.snapshot_id=t.snapshot_id").append(joins).append(" WHERE s.status='complete' AND s.schema_version=")
                    .append(ApocBRPlayerRelationalCapture.SCHEMA_VERSION).append(" AND s.codec_version=").append(ApocBRPlayerRelationalCapture.CODEC_VERSION);
        }
        return sql.toString();
    }
    Profile load(String world,String identity,int slot,boolean coop) throws SQLException {
        Profile profile=loadGraph(world,identity,slot,coop);
        // The graph lease is already returned, including when poolSize=1.
        if(profile!=null) {
            Set<ApocBRPayloadProvenance> origins=new LinkedHashSet<>();
            for(var row:profile.rows("value_node")) {
                var origin=ApocBRPayloadProvenance.of(row.get("source_server_id"),row.get("source_dictionary_namespace_id"),row.get("source_dictionary_view_id"));
                if(origin!=null)origins.add(origin);
            }
            for(var origin:origins)zombie.world.ApocBRWorldDictionaryDecode.prepare(origin);
        }
        return profile;
    }
    private Profile loadGraph(String world,String identity,int slot,boolean coop) throws SQLException {
        if(slot<0||slot>=4)return null;
        try(Connection connection=connections.open()) {
            try(PreparedStatement query=connection.prepareStatement(SQL)) {
                query.setQueryTimeout(2);
                query.setString(1,world);query.setString(2,coop?"steam_coop":"username");query.setString(3,identity);query.setInt(4,slot);
                Map<String,List<Map<String,Object>>> tables=new LinkedHashMap<>();int count=0;long bytes=0;
                try(ResultSet rows=query.executeQuery()) {
                    while(rows.next()) {
                        String table=rows.getString(1),json=rows.getString(2);
                        bytes+=json.length()*2L;
                        if(++count>ApocBRPlayerRelationalLimits.ROWS+1||bytes>ApocBRPlayerRelationalLimits.GRAPH_BYTES)
                            throw new SQLException("Relational login read exceeds graph budget","54000");
                        tables.computeIfAbsent(table,k->new ArrayList<>()).add(new JSONObject(json).toMap());
                    }
                }
                if(tables.isEmpty()){connection.commit();return null;}
                Profile profile=new Profile(tables);var metadata=profile.one("snapshot");
                if(!"complete".equals(metadata.get("status"))||((Number)metadata.get("schema_version")).intValue()!=ApocBRPlayerRelationalCapture.SCHEMA_VERSION
                        ||((Number)metadata.get("codec_version")).intValue()!=ApocBRPlayerRelationalCapture.CODEC_VERSION)
                    throw new SQLException("Relational login requires a complete compatible head","22000");
                for(String table:List.of("object","item","container")) {
                    String key="expected_"+table+"_count";
                    if(profile.rows(table).size()!=((Number)metadata.get(key)).intValue())throw new SQLException("Relational login graph count mismatch","22000");
                }
                int edges=profile.rows("item_location").size()+profile.rows("equipment").size();
                if(edges!=((Number)metadata.get("expected_edge_count")).intValue())throw new SQLException("Relational login edge count mismatch","22000");
                connection.commit();return profile;
            } catch(SQLException|RuntimeException failure) {
                try{connection.rollback();}catch(SQLException ignored){}
                // Do not expose SQL, row contents or identity through driver diagnostics.
                throw new SQLException("Relational player read failed",failure instanceof SQLException sql?sql.getSQLState():"22000");
            }
        }
    }
}
