package zombie.savefile;

import java.sql.*;
import java.util.*;
import zombie.apocalipse.humans.death.HumanDeathPolicy;

/** Worker-only mortality configuration hydration, never native world access. */
final class ApocBRNpcMortality {
    static HumanDeathPolicy read(Connection connection,String respawn,String loot)throws SQLException {
        boolean enabled;long delay;
        try(var s=ApocBRNpcStore.statement(connection,"SELECT enabled,delay_ms FROM npc_engine.respawn_profile WHERE respawn_profile_id=?",respawn);var r=s.executeQuery()){
            if(!r.next())throw new SQLException("Missing NPC respawn profile","22023");enabled=r.getBoolean(1);delay=r.getLong(2);
        }
        int rolls;List<Integer> weights;
        try(var s=ApocBRNpcStore.statement(connection,"SELECT rolls,common_weight,rare_weight,epic_weight,legendary_weight FROM npc_engine.loot_table WHERE loot_table_id=?",loot);var r=s.executeQuery()){
            if(!r.next())throw new SQLException("Missing NPC loot table","22023");rolls=r.getInt(1);weights=List.of(r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5));
        }
        var entries=new ArrayList<HumanDeathPolicy.Entry>();
        try(var s=ApocBRNpcStore.statement(connection,"SELECT rarity,item_type,weight,minimum_quantity,maximum_quantity FROM npc_engine.loot_entry WHERE loot_table_id=? ORDER BY rarity,item_type LIMIT 129",loot);var r=s.executeQuery()){
            while(r.next()){
                if(entries.size()>=128)throw new SQLException("NPC loot entry limit exceeded","22023");
                entries.add(new HumanDeathPolicy.Entry(HumanDeathPolicy.Rarity.valueOf(r.getString(1)),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)));
            }
        }
        return new HumanDeathPolicy(respawn,enabled,delay,new HumanDeathPolicy.LootTable(loot,rolls,weights,entries));
    }
}
