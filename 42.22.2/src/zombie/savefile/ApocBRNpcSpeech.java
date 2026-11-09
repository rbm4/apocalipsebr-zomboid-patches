package zombie.savefile;

import java.sql.*;
import java.util.*;
import zombie.apocalipse.humans.speech.*;

/** Worker-only content hydration and epoch/revision-fenced semantic-memory journal adapter. */
final class ApocBRNpcSpeech {
    static HumanSpeechCatalog catalog(Connection c)throws SQLException {
        var rules=new EnumMap<SpeechEvent,HumanSpeechCatalog.Rule>(SpeechEvent.class);
        try(var s=ApocBRNpcStore.statement(c,"SELECT event,chance,cooldown_ms,priority FROM npc_engine.speech_rule ORDER BY event LIMIT 65");var r=s.executeQuery()){
            while(r.next())rules.put(SpeechEvent.valueOf(r.getString(1)),new HumanSpeechCatalog.Rule(r.getInt(2),r.getLong(3),r.getInt(4)));
        }
        var lines=new ArrayList<HumanSpeechCatalog.Line>();
        try(var s=ApocBRNpcStore.statement(c,"SELECT message_id,event,locale,text,weight,min_health,max_health,attack_kind,temperament,min_aggressors FROM npc_engine.speech_pool_line WHERE enabled ORDER BY message_id,locale LIMIT 4097");var r=s.executeQuery()){
            while(r.next()){
                if(lines.size()>=4096)throw new SQLException("Speech catalog capacity","22023");
                lines.add(new HumanSpeechCatalog.Line(r.getString(1),SpeechEvent.valueOf(r.getString(2)),r.getString(3),r.getString(4),r.getInt(5),r.getFloat(6),r.getFloat(7),
                    SpeechContext.AttackKind.valueOf(r.getString(8)),r.getString(9),r.getInt(10)));
            }
        }
        return new HumanSpeechCatalog(rules,lines);
    }
    static Map<HumanSpeechMemory.Key,HumanSpeechMemory.State> read(Connection c,String server,String world)throws SQLException {
        var values=new HashMap<HumanSpeechMemory.Key,HumanSpeechMemory.State>();
        try(var s=ApocBRNpcStore.statement(c,"SELECT npc_key,identity_kind,player_identity,mutation_revision,last_attack_ms,attack_count,recent_ids,codec_version FROM npc_engine.speech_memory WHERE server_key=? AND world=? LIMIT 100001",server,world);var r=s.executeQuery()){
            while(r.next()){
                if(values.size()>=HumanSpeechMemory.CAPACITY||r.getInt(8)!=1)throw new SQLException("Invalid speech memory batch","22023");
                String ids=r.getString(7);
                values.put(new HumanSpeechMemory.Key(r.getString(1),r.getString(2),r.getString(3)),new HumanSpeechMemory.State(r.getLong(4),r.getLong(5),r.getLong(6),ids.isEmpty()?List.of():List.of(ids.split(",",-1))));
            }
        }
        return Map.copyOf(values);
    }
    static ApocBRPersistenceOperations.Sql statement(String server,String world,long epoch,HumanSpeechMemory.Change change){
        var k=change.key();var v=change.state();
        return new ApocBRPersistenceOperations.Sql("""
            INSERT INTO npc_engine.speech_memory(server_key,world,npc_key,identity_kind,player_identity,epoch,mutation_revision,last_attack_ms,attack_count,recent_ids,codec_version)
            SELECT ?,?,?,?,?,?,?,?,?,?,1 WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
            ON CONFLICT(server_key,world,npc_key,identity_kind,player_identity) DO UPDATE SET
            epoch=EXCLUDED.epoch,mutation_revision=EXCLUDED.mutation_revision,last_attack_ms=EXCLUDED.last_attack_ms,
            attack_count=EXCLUDED.attack_count,recent_ids=EXCLUDED.recent_ids,codec_version=EXCLUDED.codec_version
            WHERE speech_memory.epoch<EXCLUDED.epoch OR (speech_memory.epoch=EXCLUDED.epoch AND speech_memory.mutation_revision<EXCLUDED.mutation_revision)
            """,List.of(server,world,k.npc(),k.kind(),k.account(),epoch,v.revision(),v.lastAttackMs(),v.attacks(),String.join(",",v.recent()),server,world,epoch));
    }
    static void flush(HumanSpeechMemory memory,String server,String world,long epoch){
        for(var change:memory.pending()){
            ApocBRPersistence.submit(new ApocBRPersistenceOperations.Account(List.of(statement(server,world,epoch,change))));
            memory.staged(change);
        }
    }
}
