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
    /** Only local allocation-history conflicts permit automatic namespace rollover. */
    public static final class HistoryChangedException extends SQLException {
        private HistoryChangedException(String message){super(message,"22023");}
    }
    public static final String CHANNEL="apocbr_dictionary_changed";
    private final Connections connections;
    public ApocBRWorldDictionaryDatabase(){this(ApocBRPostgresConnector::borrowDictionaryConnection);}
    public ApocBRWorldDictionaryDatabase(Connections connections){this.connections=connections;}
    private static PreparedStatement prepare(Connection connection,String sql)throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql);
        try{result.setQueryTimeout(5);return result;}catch(SQLException error){result.close();throw error;}
    }
    private static PreparedStatement preparePublication(Connection connection,String sql)throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql);
        try{result.setQueryTimeout(ApocBRPostgresConnector.dictionaryPublicationTimeoutSeconds());return result;}catch(SQLException|RuntimeException error){result.close();throw error;}
    }
    private static void bind(PreparedStatement statement,Object... values)throws SQLException {for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);}
    private static int update(Connection connection,String sql,Object... values)throws SQLException {
        try(PreparedStatement statement=preparePublication(connection,sql)){bind(statement,values);return statement.executeUpdate();}
    }
    public Published publish(Origin origin,ApocBRWorldDictionarySnapshot snapshot)throws SQLException {
        UUID view=UUID.randomUUID();String stage="ACQUIRE_CONNECTION";
        long started=System.nanoTime(),stageStarted=started;
        try(Connection connection=connections.open()) {
            try {
                stage="SET_LOCK_TIMEOUT";stageStarted=System.nanoTime();
                update(connection,"SET LOCAL lock_timeout = '2s'");
                stage="PROVISION_NAMESPACE";stageStarted=System.nanoTime();
                update(connection,"INSERT INTO player_rel.world_dictionary_namespace(id,name,game_build,world_version,dictionary_format_version) VALUES(?,?,'42.22.0',251,1) ON CONFLICT(id) DO NOTHING",origin.namespace(),origin.name());
                stage="LOCK_NAMESPACE";stageStarted=System.nanoTime();
                long revision;
                try(PreparedStatement lock=preparePublication(connection,"SELECT current_revision,name,game_build,world_version,dictionary_format_version FROM player_rel.world_dictionary_namespace WHERE id=? FOR UPDATE")) {
                    bind(lock,origin.namespace());try(ResultSet rows=lock.executeQuery()) {
                        if(!rows.next()||!origin.name().equals(rows.getString(2)))throw new SQLException("Dictionary namespace configuration mismatch","22023");
                        if(!"42.22.0".equals(rows.getString(3))||rows.getInt(4)!=251||rows.getInt(5)!=1)
                            throw new SQLException("Dictionary namespace build/version mismatch; configure a new namespace","22023");
                        revision=Math.addExact(rows.getLong(1),1);
                    }
                }
                // Mirror namespaces represent one vanilla allocation history, not a central allocator.
                stage="VERIFY_NAMESPACE_OWNER";stageStarted=System.nanoTime();
                try(PreparedStatement owner=preparePublication(connection,"SELECT 1 FROM player_rel.world_dictionary_runtime_view WHERE namespace_id=? AND (server_id IS DISTINCT FROM ? OR save_key<>?) LIMIT 1")) {
                    bind(owner,origin.namespace(),origin.serverId(),origin.save());try(ResultSet rows=owner.executeQuery()){if(rows.next())throw new SQLException("Mirror namespace belongs to another server/save","22023");}
                }
                stage="READ_HISTORY";stageStarted=System.nanoTime();
                var registries=registries(connection,origin.namespace());var entries=entries(connection,origin.namespace());
                stage="VALIDATE_HISTORY";stageStarted=System.nanoTime();
                validateHistory(snapshot,registries,entries);
                stage="COMPARE_READY_VIEW";stageStarted=System.nanoTime();
                Published unchanged=unchangedView(connection,origin,snapshot,revision-1,registries,entries);
                if(unchanged!=null) {
                    stage="COMMIT_REUSE";stageStarted=System.nanoTime();connection.commit();
                    zombie.debug.DebugLog.log("[ApocBR][Dictionary] REUSED; serverId="+origin.serverId()+"; namespace="+origin.namespace()+"; view="+unchanged.view()+"; revision="+unchanged.revision());
                    stage="RETURN_CONNECTION";stageStarted=System.nanoTime();return unchanged;
                }
                stage="PROVISION_INITIAL_REVISION";stageStarted=System.nanoTime();
                update(connection,"INSERT INTO player_rel.world_dictionary_revision(namespace_id,revision,reason) VALUES(?,0,'initial') ON CONFLICT DO NOTHING",origin.namespace());
                stage="CREATE_REVISION";stageStarted=System.nanoTime();
                update(connection,"INSERT INTO player_rel.world_dictionary_revision(namespace_id,revision,reason) VALUES(?,?,'vanilla_rebuild')",origin.namespace(),revision);
                stage="WRITE_REGISTRIES";stageStarted=System.nanoTime();
                try(Batch registry=new Batch(connection,"INSERT INTO player_rel.world_dictionary_registry(namespace_id,family,register_name,next_id,string_max_length,created_revision) VALUES(?,?,?,?,?,?) ON CONFLICT(namespace_id,family,register_name) DO UPDATE SET next_id=EXCLUDED.next_id")) {
                    for(var r:snapshot.registries())registry.add(origin.namespace(),r.family(),r.name(),r.nextId(),r.maxLength(),revision);
                }
                stage="WRITE_IDENTITIES";stageStarted=System.nanoTime();
                try(Batch identities=new Batch(connection,"INSERT INTO player_rel.world_dictionary_entry(namespace_id,family,register_name,native_id,kind,stable_key,module_name,local_name,created_revision) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(var e:snapshot.entries())if(!entries.containsKey(e.identity()))identities.add(origin.namespace(),e.family(),e.register(),e.id(),e.kind(),e.key(),e.module(),e.name(),revision);
                }
                stage="ADVANCE_REVISION";stageStarted=System.nanoTime();
                update(connection,"UPDATE player_rel.world_dictionary_namespace SET current_revision=? WHERE id=?",revision,origin.namespace());
                stage="CREATE_RUNTIME_VIEW";stageStarted=System.nanoTime();
                update(connection,"INSERT INTO player_rel.world_dictionary_runtime_view(namespace_id,id,identity_revision,server_id,save_key) VALUES(?,?,?,?,?)",origin.namespace(),view,revision,origin.serverId(),origin.save());
                stage="WRITE_REGISTRY_STATE";stageStarted=System.nanoTime();
                try(Batch counters=new Batch(connection,"INSERT INTO player_rel.world_dictionary_registry_state(namespace_id,view_id,family,register_name,next_id) VALUES(?,?,?,?,?)")) {
                    for(var r:snapshot.registries())counters.add(origin.namespace(),view,r.family(),r.name(),r.nextId());
                }
                stage="WRITE_ENTRY_STATE";stageStarted=System.nanoTime();
                try(Batch state=new Batch(connection,"INSERT INTO player_rel.world_dictionary_entry_state(namespace_id,view_id,family,register_name,native_id,kind,is_loaded,mod_id,exists_as_vanilla,is_modded,obsolete,removed,overrides_present,script_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                    Batch overrides=new Batch(connection,"INSERT INTO player_rel.world_dictionary_mod_override(namespace_id,view_id,family,register_name,native_id,kind,position,mod_id) VALUES(?,?,?,?,?,?,?,?)")) {
                    for(var e:snapshot.entries())if(!e.family().equals("object")&&!e.family().equals("sprite")) {
                        state.add(origin.namespace(),view,e.family(),e.register(),e.id(),e.kind(),e.loaded(),e.mod(),e.vanilla(),e.modded(),e.obsolete(),e.removed(),e.overridesPresent(),e.version());
                    }
                    // Flush parent state before children; close order of resources is reversed.
                    state.flush();
                    stage="WRITE_MOD_OVERRIDES";stageStarted=System.nanoTime();
                    for(var e:snapshot.entries())for(int i=0;i<e.overrides().size();i++)overrides.add(origin.namespace(),view,e.family(),e.register(),e.id(),e.kind(),i,e.overrides().get(i));
                }
                stage="PUBLISH_READY_VIEW";stageStarted=System.nanoTime();
                update(connection,"UPDATE player_rel.world_dictionary_runtime_view SET status='ready',published_at=clock_timestamp() WHERE namespace_id=? AND id=?",origin.namespace(),view);
                String event=new JSONObject().put("namespace_id",origin.namespace().toString()).put("view_id",view.toString())
                        .put("revision",Long.toString(revision)).put("server_id",origin.serverId()).put("save_key",origin.save()).toString();
                stage="WRITE_JOURNAL";stageStarted=System.nanoTime();
                update(connection,"INSERT INTO player_rel.world_dictionary_journal(namespace_id,revision,view_id,event_type,detail) VALUES(?,?,?,'vanilla_rebuild',?::jsonb)",origin.namespace(),revision,view,event);
                stage="NOTIFY";stageStarted=System.nanoTime();
                try(PreparedStatement notify=preparePublication(connection,"SELECT pg_notify(?,?)")){bind(notify,CHANNEL,event);try(ResultSet ignored=notify.executeQuery()){} }
                stage="COMMIT";stageStarted=System.nanoTime();
                connection.commit();stage="RETURN_CONNECTION";stageStarted=System.nanoTime();return new Published(origin.namespace(),view,revision,origin.serverId(),origin.save());
            }catch(SQLException|RuntimeException failure) {
                try{connection.rollback();}catch(SQLException ignored){}
                throw failure;
            }
        }catch(SQLException|RuntimeException failure) {
            SQLException diagnostic=publicationFailure(stage,origin,view,snapshot,failure,started,stageStarted);
            if(failure instanceof HistoryChangedException)throw new HistoryChangedException(diagnostic.getMessage());
            throw diagnostic;
        }
    }
    // Only these application-owned messages may be copied verbatim. Driver messages can contain
    // bound rows (especially BatchUpdateException), so they go through the structured PG diagnostic.
    private static final Set<String> VALIDATION_MESSAGES=Set.of(
        "Dictionary namespace configuration mismatch","Mirror namespace belongs to another server/save",
        "Vanilla dictionary history changed; use a new namespace for a new save","Vanilla dictionary lost a register",
        "Vanilla dictionary ID remapped; use a new namespace","Vanilla dictionary lost historical identities",
        "Dictionary registry budget","Dictionary identity budget","Incomplete dictionary batch","Failed dictionary batch");
    private static SQLException publicationFailure(String stage,Origin origin,UUID view,ApocBRWorldDictionarySnapshot snapshot,Throwable failure,long started,long stageStarted) {
        String state=failure instanceof SQLException sql?sql.getSQLState():"22000";
        int timeout;
        try{timeout=ApocBRPostgresConnector.dictionaryPublicationTimeoutSeconds();}catch(RuntimeException invalid){timeout=-1;}
        StringBuilder diagnostic=new StringBuilder(ApocBRPostgresConnector.sqlDiagnostic(failure,false));
        if(failure.getMessage()!=null&&VALIDATION_MESSAGES.contains(failure.getMessage()))diagnostic.append("; validation=").append(failure.getMessage());
        // pgJDBC puts the original server error in nextException for a failed batch. Its outer
        // message may include a literal INSERT with every bound value; never log that message.
        if(failure instanceof SQLException sql) {
            Set<SQLException> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.add(sql);
            SQLException next=sql.getNextException();int count=0;
            while(next!=null&&seen.add(next)&&count++<8) {
                if((state==null||state.equals("25P02"))&&next.getSQLState()!=null)state=next.getSQLState();
                diagnostic.append("; next={").append(ApocBRPostgresConnector.sqlDiagnostic(next,false)).append('}');
                next=next.getNextException();
            }
        }
        if(state==null||!state.matches("[A-Z0-9]{5}"))state="HY000";
        String hint=switch(state) {
            case "42P01","42703" -> "verify backend V6/V7 migrations in this connection's database (player_rel.world_dictionary_* and server_id)";
            case "23503" -> "verify player_rel.server.id registration and dictionary foreign keys";
            case "23514","23505" -> "inspect the reported dictionary trigger/constraint and allocation history";
            case "42501" -> "verify dictionary table/sequence privileges for the connector role";
            case "08006" -> "connection lost: inspect transport cause; dictionary socket timeout must exceed query timeout";
            case "57014","55P03" -> "dictionary statement or namespace lock timed out";
            default -> "inspect stage, SQLState and structured server error";
        };
        return new SQLException("Dictionary mirror publication failed; stage="+stage+"; SQLState="+state
            +"; elapsedMs="+(System.nanoTime()-started)/1_000_000L+"; stageMs="+(System.nanoTime()-stageStarted)/1_000_000L
            +"; publicationTimeoutSeconds="+timeout
            +"; serverId="+origin.serverId()+"; namespace="+origin.namespace()+"; view="+view
            +"; registers="+snapshot.registries().size()+"; entries="+snapshot.entries().size()
            +"; "+diagnostic+"; dictionaryHint="+hint,state);
    }
    /** Compare the complete persisted snapshot under the namespace lock, including ordered overrides.
     * No process cache or hash is used: identical startups also reuse views after a process restart. */
    private static Published unchangedView(Connection connection,Origin origin,ApocBRWorldDictionarySnapshot snapshot,long revision,
            Map<String,ApocBRWorldDictionarySnapshot.Registry> registries,Map<String,List<Object>> entries)throws SQLException {
        if(entries.size()!=snapshot.entries().size()||registries.size()!=snapshot.registries().size())return null;
        for(var r:snapshot.registries())if(!r.equals(registries.get(r.key())))return null;
        UUID view;
        try(var query=preparePublication(connection,"SELECT v.id FROM player_rel.world_dictionary_runtime_view v JOIN player_rel.world_dictionary_namespace n ON n.id=v.namespace_id WHERE v.namespace_id=? AND v.server_id=? AND v.save_key=? AND v.identity_revision=? AND v.status='ready' AND n.game_build='42.22.0' AND n.world_version=251 AND n.dictionary_format_version=1 ORDER BY v.published_at DESC,v.id DESC LIMIT 1")) {
            bind(query,origin.namespace(),origin.serverId(),origin.save(),revision);
            try(var rows=query.executeQuery()){if(!rows.next())return null;view=rows.getObject(1,UUID.class);}
        }
        Map<String,ApocBRWorldDictionarySnapshot.Registry> counters=new HashMap<>(registries);
        try(var query=preparePublication(connection,"SELECT family,register_name,next_id FROM player_rel.world_dictionary_registry_state WHERE namespace_id=? AND view_id=?")) {
            bind(query,origin.namespace(),view);try(var rows=query.executeQuery()){while(rows.next()) {
                var expected=counters.remove(rows.getString(1)+":"+rows.getString(2));
                if(expected==null||expected.nextId()!=rows.getInt(3))return null;
            }}
        }
        if(!counters.isEmpty())return null;
        Map<String,List<Object>> states=new HashMap<>();Map<String,List<String>> overrides=new HashMap<>();
        for(var e:snapshot.entries()) {
            if(!e.family().equals("object")&&!e.family().equals("sprite"))states.put(e.identity(),Arrays.asList(e.kind(),e.loaded(),e.mod(),e.vanilla(),e.modded(),e.obsolete(),e.removed(),e.overridesPresent(),e.version()));
            if(!e.overrides().isEmpty())overrides.put(e.identity(),e.overrides());
        }
        try(var query=preparePublication(connection,"SELECT family,register_name,native_id,kind,is_loaded,mod_id,exists_as_vanilla,is_modded,obsolete,removed,overrides_present,script_version FROM player_rel.world_dictionary_entry_state WHERE namespace_id=? AND view_id=?")) {
            bind(query,origin.namespace(),view);try(var rows=query.executeQuery()){while(rows.next()) {
                String key=rows.getString(1)+":"+rows.getString(2)+":"+rows.getInt(3);
                var expected=states.remove(key);
                if(expected==null||!expected.equals(Arrays.asList(rows.getString(4),rows.getObject(5),rows.getString(6),rows.getObject(7),rows.getObject(8),rows.getObject(9),rows.getObject(10),rows.getObject(11),rows.getObject(12))))return null;
            }}
        }
        if(!states.isEmpty())return null;
        // Consume expected overrides in SQL position order without depending on entry iteration order.
        Map<String,Integer> positions=new HashMap<>();
        try(var query=preparePublication(connection,"SELECT family,register_name,native_id,mod_id FROM player_rel.world_dictionary_mod_override WHERE namespace_id=? AND view_id=? ORDER BY family,register_name,native_id,position")) {
            bind(query,origin.namespace(),view);try(var rows=query.executeQuery()){while(rows.next()) {
                String key=rows.getString(1)+":"+rows.getString(2)+":"+rows.getInt(3);var expected=overrides.get(key);
                int position=positions.getOrDefault(key,0);
                if(expected==null||position>=expected.size()||!expected.get(position).equals(rows.getString(4)))return null;
                positions.put(key,position+1);
            }}
        }
        for(var e:overrides.entrySet())if(positions.getOrDefault(e.getKey(),0)!=e.getValue().size())return null;
        return new Published(origin.namespace(),view,revision,origin.serverId(),origin.save());
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
            if(previous!=null&&(r.nextId()<previous.nextId()||!Objects.equals(r.maxLength(),previous.maxLength())))throw new HistoryChangedException("Vanilla dictionary history changed; use a new namespace for a new save");
        }
        if(!seen.containsAll(registries.keySet()))throw new HistoryChangedException("Vanilla dictionary lost a register");
        seen.clear();
        for(var e:snapshot.entries()) {
            seen.add(e.identity());var old=entries.get(e.identity());
            if(old!=null&&!old.equals(Arrays.asList(e.kind(),e.key(),e.module(),e.name())))throw new HistoryChangedException("Vanilla dictionary ID remapped; use a new namespace");
        }
        if(!seen.containsAll(entries.keySet()))throw new HistoryChangedException("Vanilla dictionary lost historical identities");
    }
    /** Read one exact immutable publication, never the server's latest view as a substitute. */
    public ApocBRWorldDictionarySnapshot load(ApocBRPayloadProvenance origin)throws SQLException {
        String stage="ACQUIRE_CONNECTION";long started=System.nanoTime();
        try(Connection connection=connections.open()) {
            try {
                stage="FIND_READY_SOURCE_VIEW";
                long revision;
                try(var query=prepare(connection,"SELECT v.identity_revision FROM player_rel.world_dictionary_runtime_view v JOIN player_rel.world_dictionary_namespace n ON n.id=v.namespace_id WHERE v.namespace_id=? AND v.id=? AND v.server_id=? AND v.status='ready' AND n.game_build='42.22.0' AND n.world_version=251 AND n.dictionary_format_version=1")) {
                    bind(query,origin.namespaceId(),origin.viewId(),origin.serverId());
                    try(var rows=query.executeQuery()){if(!rows.next())throw new SQLException("Source dictionary view unavailable","55000");revision=rows.getLong(1);}
                }
                stage="READ_SOURCE_REGISTRIES";List<ApocBRWorldDictionarySnapshot.Registry> registers=new ArrayList<>();
                try(var query=prepare(connection,"SELECT s.family,s.register_name,s.next_id,r.string_max_length FROM player_rel.world_dictionary_registry_state s JOIN player_rel.world_dictionary_registry r USING(namespace_id,family,register_name) WHERE s.namespace_id=? AND s.view_id=?")) {
                    bind(query,origin.namespaceId(),origin.viewId());try(var rows=query.executeQuery()){while(rows.next()) {
                        if(registers.size()>=128)throw new SQLException("Dictionary registry budget","54000");
                        registers.add(new ApocBRWorldDictionarySnapshot.Registry(rows.getString(1),rows.getString(2),rows.getInt(3),(Integer)rows.getObject(4)));
                    }}
                }
                stage="READ_SOURCE_OVERRIDES";Map<String,List<String>> overrides=new HashMap<>();int count=0;
                try(var query=prepare(connection,"SELECT family,register_name,native_id,mod_id FROM player_rel.world_dictionary_mod_override WHERE namespace_id=? AND view_id=? ORDER BY family,register_name,native_id,position")) {
                    bind(query,origin.namespaceId(),origin.viewId());try(var rows=query.executeQuery()){while(rows.next()) {
                        if(++count>1_000_000)throw new SQLException("Dictionary override budget","54000");
                        overrides.computeIfAbsent(rows.getString(1)+":"+rows.getString(2)+":"+rows.getInt(3),key->new ArrayList<>()).add(rows.getString(4));
                    }}
                }
                stage="READ_SOURCE_ENTRIES";List<ApocBRWorldDictionarySnapshot.Entry> entries=new ArrayList<>();
                try(var query=prepare(connection,"SELECT e.family,e.register_name,e.native_id,e.kind,e.stable_key,e.module_name,e.local_name,s.is_loaded,s.mod_id,s.exists_as_vanilla,s.is_modded,s.obsolete,s.removed,s.overrides_present,s.script_version FROM player_rel.world_dictionary_entry e LEFT JOIN player_rel.world_dictionary_entry_state s ON s.namespace_id=e.namespace_id AND s.family=e.family AND s.register_name=e.register_name AND s.native_id=e.native_id AND s.view_id=? WHERE e.namespace_id=? AND e.created_revision<=?")) {
                    bind(query,origin.viewId(),origin.namespaceId(),revision);try(var rows=query.executeQuery()){while(rows.next()) {
                        if(entries.size()>=200_000)throw new SQLException("Dictionary entry budget","54000");
                        String family=rows.getString(1),register=rows.getString(2);int id=rows.getInt(3);
                        Boolean loaded=(Boolean)rows.getObject(8);
                        if(loaded==null&&!family.equals("object")&&!family.equals("sprite"))throw new SQLException("Incomplete source dictionary view","55000");
                        entries.add(new ApocBRWorldDictionarySnapshot.Entry(family,register,id,rows.getString(4),rows.getString(5),rows.getString(6),rows.getString(7),Boolean.TRUE.equals(loaded),rows.getString(9),(Boolean)rows.getObject(10),(Boolean)rows.getObject(11),(Boolean)rows.getObject(12),(Boolean)rows.getObject(13),(Boolean)rows.getObject(14),(Long)rows.getObject(15),overrides.getOrDefault(family+":"+register+":"+id,List.of())));
                    }}
                }
                if(registers.isEmpty())throw new SQLException("Empty source dictionary view","55000");
                var result=new ApocBRWorldDictionarySnapshot(registers,entries);connection.commit();return result;
            }catch(SQLException|RuntimeException failure){try{connection.rollback();}catch(SQLException ignored){}
                String reason=Set.of("Source dictionary view unavailable","Incomplete source dictionary view","Empty source dictionary view","Dictionary registry budget","Dictionary entry budget","Dictionary override budget").contains(failure.getMessage()==null?"":failure.getMessage())?failure.getMessage():"SQL_OR_RUNTIME_FAILURE";
                zombie.debug.DebugLog.log("[ApocBR][Dictionary] SOURCE_READ_FAILED; stage="+stage+"; sourceServer="+origin.serverId()+"; namespace="+origin.namespaceId()+"; view="+origin.viewId()
                    +"; elapsedMs="+(System.nanoTime()-started)/1_000_000L+"; reason="+reason+"; "+ApocBRPostgresConnector.sqlDiagnostic(failure,false));
                throw new SQLException("Source dictionary read failed",failure instanceof SQLException sql?sql.getSQLState():"22000");}
        }
    }
    /** Bounded foreign head discovery; the listener prepares each exact view before publishing metadata. */
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
        Batch(Connection connection,String sql)throws SQLException{statement=preparePublication(connection,sql);}
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
