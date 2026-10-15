package zombie.savefile;

import java.sql.*;
import java.util.*;
import org.json.JSONObject;
import zombie.world.ApocBRWorldDictionarySnapshot;

/** V6/V7 typed mirror repository. Vanilla local files still allocate and load dictionary IDs. */
public final class ApocBRWorldDictionaryDatabase {
    @FunctionalInterface public interface Connections {Connection open()throws SQLException;}
    public record Origin(UUID namespace,String name,int serverId,String save) {}
    public record Published(UUID namespace,UUID view,long revision,int serverId,String save) {}
    public static final String CHANNEL="apocbr_dictionary_changed";
    private final Connections connections;
    public ApocBRWorldDictionaryDatabase(){this(ApocBRPostgresConnector::borrowDictionaryConnection);}
    public ApocBRWorldDictionaryDatabase(Connections connections){this.connections=connections;}
    private static PreparedStatement prepare(Connection connection,String sql)throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql);
        try{result.setQueryTimeout(5);return result;}catch(SQLException error){result.close();throw error;}
    }
    private static void bind(PreparedStatement statement,Object... values)throws SQLException {for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);}
    private static int update(Connection connection,String sql,Object... values)throws SQLException {
        try(PreparedStatement statement=prepare(connection,sql)){bind(statement,values);return statement.executeUpdate();}
    }
    public Published publish(Origin origin,ApocBRWorldDictionarySnapshot snapshot)throws SQLException {
        UUID view=UUID.randomUUID();
        try(Connection connection=connections.open()) {
            try {
                update(connection,"SET LOCAL lock_timeout = '2s'");
                update(connection,"INSERT INTO player_rel.world_dictionary_namespace(id,name,game_build,world_version,dictionary_format_version) VALUES(?,?,'42.21.0',249,1) ON CONFLICT(id) DO NOTHING",origin.namespace(),origin.name());
                long revision;
                try(PreparedStatement lock=prepare(connection,"SELECT current_revision,name FROM player_rel.world_dictionary_namespace WHERE id=? FOR UPDATE")) {
                    bind(lock,origin.namespace());try(ResultSet rows=lock.executeQuery()) {
                        if(!rows.next()||!origin.name().equals(rows.getString(2)))throw new SQLException("Dictionary namespace configuration mismatch","22023");
                        revision=Math.addExact(rows.getLong(1),1);
                    }
                }
                // Mirror namespaces represent one vanilla allocation history, not a central allocator.
                try(PreparedStatement owner=prepare(connection,"SELECT 1 FROM player_rel.world_dictionary_runtime_view WHERE namespace_id=? AND (server_id IS DISTINCT FROM ? OR save_key<>?) LIMIT 1")) {
                    bind(owner,origin.namespace(),origin.serverId(),origin.save());try(ResultSet rows=owner.executeQuery()){if(rows.next())throw new SQLException("Mirror namespace belongs to another server/save","22023");}
                }
                update(connection,"INSERT INTO player_rel.world_dictionary_revision(namespace_id,revision,reason) VALUES(?,0,'initial') ON CONFLICT DO NOTHING",origin.namespace());
                update(connection,"INSERT INTO player_rel.world_dictionary_revision(namespace_id,revision,reason) VALUES(?,?,'vanilla_rebuild')",origin.namespace(),revision);
                var registries=registries(connection,origin.namespace());var entries=entries(connection,origin.namespace());
                validateHistory(snapshot,registries,entries);
                try(Batch registry=new Batch(connection,"INSERT INTO player_rel.world_dictionary_registry(namespace_id,family,register_name,next_id,string_max_length,created_revision) VALUES(?,?,?,?,?,?) ON CONFLICT(namespace_id,family,register_name) DO UPDATE SET next_id=EXCLUDED.next_id")) {
                    for(var r:snapshot.registries())registry.add(origin.namespace(),r.family(),r.name(),r.nextId(),r.maxLength(),revision);
                }
                try(Batch identities=new Batch(connection,"INSERT INTO player_rel.world_dictionary_entry(namespace_id,family,register_name,native_id,kind,stable_key,module_name,local_name,created_revision) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(var e:snapshot.entries())if(!entries.containsKey(e.identity()))identities.add(origin.namespace(),e.family(),e.register(),e.id(),e.kind(),e.key(),e.module(),e.name(),revision);
                }
                update(connection,"UPDATE player_rel.world_dictionary_namespace SET current_revision=? WHERE id=?",revision,origin.namespace());
                update(connection,"INSERT INTO player_rel.world_dictionary_runtime_view(namespace_id,id,identity_revision,server_id,save_key) VALUES(?,?,?,?,?)",origin.namespace(),view,revision,origin.serverId(),origin.save());
                try(Batch counters=new Batch(connection,"INSERT INTO player_rel.world_dictionary_registry_state(namespace_id,view_id,family,register_name,next_id) VALUES(?,?,?,?,?)")) {
                    for(var r:snapshot.registries())counters.add(origin.namespace(),view,r.family(),r.name(),r.nextId());
                }
                try(Batch state=new Batch(connection,"INSERT INTO player_rel.world_dictionary_entry_state(namespace_id,view_id,family,register_name,native_id,kind,is_loaded,mod_id,exists_as_vanilla,is_modded,obsolete,removed,overrides_present,script_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                    Batch overrides=new Batch(connection,"INSERT INTO player_rel.world_dictionary_mod_override(namespace_id,view_id,family,register_name,native_id,kind,position,mod_id) VALUES(?,?,?,?,?,?,?,?)")) {
                    for(var e:snapshot.entries())if(!e.family().equals("object")&&!e.family().equals("sprite")) {
                        state.add(origin.namespace(),view,e.family(),e.register(),e.id(),e.kind(),e.loaded(),e.mod(),e.vanilla(),e.modded(),e.obsolete(),e.removed(),e.overridesPresent(),e.version());
                    }
                    // Flush parent state before children; close order of resources is reversed.
                    state.flush();
                    for(var e:snapshot.entries())for(int i=0;i<e.overrides().size();i++)overrides.add(origin.namespace(),view,e.family(),e.register(),e.id(),e.kind(),i,e.overrides().get(i));
                }
                update(connection,"UPDATE player_rel.world_dictionary_runtime_view SET status='ready',published_at=clock_timestamp() WHERE namespace_id=? AND id=?",origin.namespace(),view);
                String event=new JSONObject().put("namespace_id",origin.namespace().toString()).put("view_id",view.toString())
                        .put("revision",Long.toString(revision)).put("server_id",origin.serverId()).put("save_key",origin.save()).toString();
                update(connection,"INSERT INTO player_rel.world_dictionary_journal(namespace_id,revision,view_id,event_type,detail) VALUES(?,?,?,'vanilla_rebuild',?::jsonb)",origin.namespace(),revision,view,event);
                try(PreparedStatement notify=prepare(connection,"SELECT pg_notify(?,?)")){bind(notify,CHANNEL,event);try(ResultSet ignored=notify.executeQuery()){} }
                connection.commit();return new Published(origin.namespace(),view,revision,origin.serverId(),origin.save());
            }catch(SQLException|RuntimeException failure) {
                try{connection.rollback();}catch(SQLException ignored){}
                throw new SQLException("Dictionary mirror publication failed",failure instanceof SQLException sql?sql.getSQLState():"22000");
            }
        }
    }
    private static Map<String,ApocBRWorldDictionarySnapshot.Registry> registries(Connection connection,UUID namespace)throws SQLException {
        Map<String,ApocBRWorldDictionarySnapshot.Registry> result=new HashMap<>();
        try(PreparedStatement query=prepare(connection,"SELECT family,register_name,next_id,string_max_length FROM player_rel.world_dictionary_registry WHERE namespace_id=?")) {
            bind(query,namespace);try(ResultSet rows=query.executeQuery()){while(rows.next()) {
                if(result.size()>=128)throw new SQLException("Dictionary registry budget","54000");
                var r=new ApocBRWorldDictionarySnapshot.Registry(rows.getString(1),rows.getString(2),rows.getInt(3),(Integer)rows.getObject(4));result.put(r.key(),r);
            }}
        }return result;
    }
    private static Map<String,List<Object>> entries(Connection connection,UUID namespace)throws SQLException {
        Map<String,List<Object>> result=new HashMap<>();
        try(PreparedStatement query=prepare(connection,"SELECT family,register_name,native_id,kind,stable_key,module_name,local_name FROM player_rel.world_dictionary_entry WHERE namespace_id=?")) {
            bind(query,namespace);try(ResultSet rows=query.executeQuery()){while(rows.next()) {
                if(result.size()>=200_000)throw new SQLException("Dictionary identity budget","54000");
                result.put(rows.getString(1)+":"+rows.getString(2)+":"+rows.getInt(3),Arrays.asList(rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7)));
            }}
        }return result;
    }
    static void validateHistory(ApocBRWorldDictionarySnapshot snapshot,Map<String,ApocBRWorldDictionarySnapshot.Registry> registries,Map<String,List<Object>> entries)throws SQLException {
        Set<String> seen=new HashSet<>();
        for(var r:snapshot.registries()) {
            seen.add(r.key());var previous=registries.get(r.key());
            if(previous!=null&&(r.nextId()<previous.nextId()||!Objects.equals(r.maxLength(),previous.maxLength())))throw new SQLException("Vanilla dictionary history changed; use a new namespace for a new save","22023");
        }
        if(!seen.containsAll(registries.keySet()))throw new SQLException("Vanilla dictionary lost a register","22023");
        seen.clear();
        for(var e:snapshot.entries()) {
            seen.add(e.identity());var old=entries.get(e.identity());
            if(old!=null&&!old.equals(Arrays.asList(e.kind(),e.key(),e.module(),e.name())))throw new SQLException("Vanilla dictionary ID remapped; use a new namespace","22023");
        }
        if(!seen.containsAll(entries.keySet()))throw new SQLException("Vanilla dictionary lost historical identities","22023");
    }
    /** Bounded metadata discovery only. No foreign map installation or decoding in this phase. */
    public List<Published> foreignHeads(int server)throws SQLException {
        try(Connection connection=connections.open()) {
            try(PreparedStatement query=prepare(connection,"SELECT DISTINCT ON(namespace_id,server_id,save_key) namespace_id,id,identity_revision,server_id,save_key FROM player_rel.world_dictionary_runtime_view WHERE status='ready' AND server_id<>? ORDER BY namespace_id,server_id,save_key,identity_revision DESC,published_at DESC,id DESC LIMIT 257")) {
                bind(query,server);List<Published> result=new ArrayList<>();
                try(ResultSet rows=query.executeQuery()){while(rows.next()){if(result.size()==256)throw new SQLException("Foreign dictionary discovery budget","54000");result.add(new Published(rows.getObject(1,UUID.class),rows.getObject(2,UUID.class),rows.getLong(3),rows.getInt(4),rows.getString(5)));}}
                connection.commit();return List.copyOf(result);
            }catch(SQLException failure){try{connection.rollback();}catch(SQLException ignored){}throw new SQLException("Foreign dictionary discovery failed",failure.getSQLState());}
        }
    }
    private static final class Batch implements AutoCloseable {
        final PreparedStatement statement;int pending;
        Batch(Connection connection,String sql)throws SQLException{statement=prepare(connection,sql);}
        void add(Object... values)throws SQLException{bind(statement,values);statement.addBatch();if(++pending==128)flush();}
        void flush()throws SQLException {
            if(pending==0)return;int[] results=statement.executeBatch();
            if(results.length!=pending)throw new SQLException("Incomplete dictionary batch","22000");
            for(int count:results)if(count==Statement.EXECUTE_FAILED||count==0)throw new SQLException("Failed dictionary batch","22000");
            statement.clearBatch();pending=0;
        }
        public void close()throws SQLException{try{flush();}finally{statement.close();}}
    }
}
