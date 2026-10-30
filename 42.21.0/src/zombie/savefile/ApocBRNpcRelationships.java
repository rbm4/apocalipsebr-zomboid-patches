package zombie.savefile;

import java.sql.*;
import java.util.*;
import zombie.apocalipse.humans.encounters.*;

/** Worker-only JDBC mapping. Each event and its absolute relationship projection share one journal transaction. */
final class ApocBRNpcRelationships {
    static Map<HumanRelationships.Key,HumanRelationships.State> read(Connection c,String server,String world)throws SQLException {
        var result=new HashMap<HumanRelationships.Key,HumanRelationships.State>();
        try(var s=ApocBRNpcStore.statement(c,"SELECT affiliation_id,identity_kind,player_identity,mutation_revision,meetings,complied,refused,permission_until_ms,denied_until_ms,next_encounter_ms FROM npc_engine.player_relationship WHERE server_key=? AND world=? LIMIT 100001",server,world);var r=s.executeQuery()){
            while(r.next()){
                if(result.size()>=HumanRelationships.CAPACITY)throw new SQLException("Relationship cache full","22023");
                result.put(new HumanRelationships.Key(r.getString(1),r.getString(2),r.getString(3)),
                    new HumanRelationships.State(r.getLong(4),r.getLong(5),r.getLong(6),r.getLong(7),r.getLong(8),r.getLong(9),r.getLong(10)));
            }
        }
        return Map.copyOf(result);
    }
    static EncounterPolicy policy(Connection c,String profile,int revision,List<zombie.apocalipse.humans.profiles.HumanModuleBinding> modules)throws SQLException {
        try(var s=ApocBRNpcStore.statement(c,"SELECT affiliation_id,challenge_tiles,warning_ms,compliance_ms,cooldown_ms,permission_ms,refusal_ms FROM npc_engine.profile_encounter WHERE profile_id=? AND revision=?",profile,revision);var r=s.executeQuery()){
            if(r.next())return new EncounterPolicy(r.getString(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getInt(7));
            if(modules.stream().anyMatch(m->m.moduleId().equals("player-encounter")))throw new SQLException("Missing encounter policy","22023");
            return EncounterPolicy.DISABLED;
        }
    }
    static List<ApocBRPersistenceOperations.Sql> statements(String server,String world,long epoch,HumanRelationships.Event event){
        var k=event.key();var v=event.state();
        var history=new ApocBRPersistenceOperations.Sql("""
            INSERT INTO npc_engine.relationship_event(server_key,world,event_id,affiliation_id,identity_kind,player_identity,npc_key,outcome,occurred_ms,mutation_revision)
            SELECT ?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
            ON CONFLICT(server_key,world,event_id) DO NOTHING
            """,List.of(server,world,event.id(),k.affiliation(),k.kind(),k.account(),event.npc(),event.outcome().name(),event.atMs(),v.revision(),server,world,epoch));
        var state=new ApocBRPersistenceOperations.Sql("""
            INSERT INTO npc_engine.player_relationship(server_key,world,affiliation_id,identity_kind,player_identity,epoch,mutation_revision,meetings,complied,refused,permission_until_ms,denied_until_ms,next_encounter_ms)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM npc_engine.scope WHERE server_key=? AND world=? AND epoch=? FOR SHARE)
            ON CONFLICT(server_key,world,affiliation_id,identity_kind,player_identity) DO UPDATE SET
            epoch=EXCLUDED.epoch,mutation_revision=EXCLUDED.mutation_revision,meetings=EXCLUDED.meetings,complied=EXCLUDED.complied,refused=EXCLUDED.refused,
            permission_until_ms=EXCLUDED.permission_until_ms,denied_until_ms=EXCLUDED.denied_until_ms,next_encounter_ms=EXCLUDED.next_encounter_ms
            WHERE player_relationship.epoch<EXCLUDED.epoch OR (player_relationship.epoch=EXCLUDED.epoch AND player_relationship.mutation_revision<EXCLUDED.mutation_revision)
            """,List.of(server,world,k.affiliation(),k.kind(),k.account(),epoch,v.revision(),v.meetings(),v.complied(),v.refused(),v.permissionUntilMs(),v.deniedUntilMs(),v.nextEncounterMs(),server,world,epoch));
        return List.of(history,state);
    }
    static void flush(HumanRelationships memory,String server,String world,long epoch){
        for(var event:memory.pending()){
            ApocBRPersistence.submit(new ApocBRPersistenceOperations.Account(statements(server,world,epoch,event)));
            memory.staged(event.id());
        }
    }
}
