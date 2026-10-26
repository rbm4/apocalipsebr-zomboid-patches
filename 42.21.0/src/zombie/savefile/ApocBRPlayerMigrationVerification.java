package zombie.savefile;

import java.util.*;
import java.util.function.Consumer;
import zombie.characters.IsoPlayer;

/** Read-only migration assertions. Native objects never escape the server-thread portion. */
final class ApocBRPlayerMigrationVerification {
    static final class AssertionFailure extends IllegalStateException {
        final String code;
        AssertionFailure(String code){super(code);this.code=code;}
    }
    record Restored(ApocBRPlayerRelationalSnapshot.Snapshot snapshot,int payloadBytes) {}
    static Restored reconstruct(ApocBRPlayerRelationalReader.Profile profile,int slot,String username,
                                Consumer<String> stage,Consumer<String> passed)throws Exception {
        stage.accept("RECONSTRUCT_PLAYER");
        IsoPlayer player=ApocBRPlayerRelationalReconstruction.reconstruct(profile,slot);
        require(player!=null,"RECONSTRUCTED_PLAYER_MISSING");
        require(player.serverPlayerIndex==slot,"RECONSTRUCTED_SLOT_MISMATCH");
        passed.accept("RECONSTRUCT_PLAYER");
        stage.accept("COPY_RECONSTRUCTED_STATE");
        var snapshot=ApocBRPlayerRelationalSnapshot.copy(player);
        passed.accept("COPY_RECONSTRUCTED_STATE");
        stage.accept("ENCODE_CLIENT_PROFILE");
        var encoded=ServerPlayerDB.encodePostgresClientProfile(player,profile,slot,username);
        byte[] bytes=encoded==null?null:encoded.data();
        require(bytes!=null&&bytes.length>0,"EMPTY_CLIENT_PROFILE");
        require(bytes.length<=ApocBRPlayerRelationalLimits.SOURCE_BYTES,"CLIENT_PROFILE_SIZE_BUDGET");
        passed.accept("ENCODE_CLIENT_PROFILE");
        return new Restored(snapshot,bytes.length);
    }
    static void identity(ApocBRPlayerRelationalReader.Profile profile,UUID character,UUID snapshot,UUID dataset) {
        require(profile!=null,"PROFILE_MISSING");
        var metadata=profile.one("snapshot");
        require(character.toString().equals(String.valueOf(metadata.get("persisted_character_id"))),"CHARACTER_CHANGED");
        require(snapshot!=null&&snapshot.toString().equals(String.valueOf(metadata.get("snapshot_id"))),"SNAPSHOT_CHANGED");
        require(dataset.toString().equals(String.valueOf(metadata.get("source_dataset_id"))),"SOURCE_DATASET_CHANGED");
    }
    static void compare(ApocBRPlayerRelationalReader.Profile source,ApocBRPlayerRelationalCapture.Graph graph,Consumer<String> passed) {
        Map<String,List<Map<String,Object>>> restored=new HashMap<>();
        for(var row:graph.rows())restored.computeIfAbsent(row.table(),key->new ArrayList<>()).add(row.values());
        // UUIDs become ordinals in the reader; compare counts and ownership through the normal reconstruction validator.
        for(String table:List.of("item","container","equipment","item_location")) {
            require(source.rows(table).size()==restored.getOrDefault(table,List.of()).size(),"ROW_COUNT_MISMATCH_"+table);
            passed.accept("COUNT_"+table);
        }
        require(equipment(source.rows("item"),source.rows("equipment")).equals(equipment(restored.getOrDefault("item",List.of()),restored.getOrDefault("equipment",List.of()))),"EQUIPMENT_TARGET_MISMATCH");
        passed.accept("EQUIPMENT_TARGETS");
        compareTable(source,restored,"item","native_id",List.of("full_type","current_uses","condition","current_ammo_count"));passed.accept("ITEM_STATE");
        // Stable natural keys allow values to be checked independently of UUID/ordinal allocation and row ordering.
        compareTable(source,restored,"character_trait","trait_key",List.of("trait_key"));passed.accept("TRAITS");
        compareTable(source,restored,"character_stat","stat_key",List.of("value"));passed.accept("STATS");
        compareTable(source,restored,"character_perk","perk_key",List.of("xp","level","descriptor_xp_boost","multiplier","multiplier_min_level","multiplier_max_level"));passed.accept("PERKS_XP");
        compareTable(source,restored,"body_part","body_part_key",List.of("health","cut","bitten","scratched","bandaged","bleeding","deep_wounded","infected"));passed.accept("BODY_PART_HEALTH");
        compareTable(source,restored,"character_state",null,List.of("is_dead","hours_survived","zombie_kills","survivor_kills","total_xp","xp_level","xp_last_level"));passed.accept("CHARACTER_STATE");
    }
    private static Map<List<Object>,Long> equipment(List<Map<String,Object>> items,List<Map<String,Object>> equipment) {
        Map<String,Long> ids=new HashMap<>();
        for(var item:items)ids.put(String.valueOf(item.get("id")),((Number)item.get("native_id")).longValue());
        Map<List<Object>,Long> targets=new HashMap<>();
        for(var row:equipment) {
            Long target=ids.get(String.valueOf(row.get("item_id")));require(target!=null,"EQUIPMENT_TARGET_MISSING");
            var key=List.of(row.get("kind"),row.get("slot"),((Number)row.get("position")).intValue());
            require(targets.put(key,target)==null,"DUPLICATE_EQUIPMENT_SLOT");
        }
        return targets;
    }
    private static void compareTable(ApocBRPlayerRelationalReader.Profile source,Map<String,List<Map<String,Object>>> restored,
                                     String table,String key,List<String> fields) {
        var expected=index(source.rows(table),key);var actual=index(restored.getOrDefault(table,List.of()),key);
        require(expected.keySet().equals(actual.keySet()),"KEYS_MISMATCH_"+table);
        for(var entry:expected.entrySet())for(String field:fields)
            require(equalValue(entry.getValue().get(field),actual.get(entry.getKey()).get(field)),"VALUE_MISMATCH_"+table+"."+field);
    }
    private static Map<Object,Map<String,Object>> index(List<Map<String,Object>> rows,String key) {
        Map<Object,Map<String,Object>> result=new HashMap<>();
        for(var row:rows)require(result.put(key==null?"singleton":Objects.requireNonNull(row.get(key)),row)==null,"DUPLICATE_COMPARISON_KEY");
        return result;
    }
    static boolean equalValue(Object a,Object b) {
        if(a instanceof Number left&&b instanceof Number right) {
            double x=left.doubleValue(),y=right.doubleValue();
            return Double.compare(x,y)==0||Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x-y)<=Math.max(1e-5,Math.max(Math.abs(x),Math.abs(y))*1e-6);
        }
        return Objects.equals(a,b);
    }
    static void require(boolean condition,String code){if(!condition)throw new AssertionFailure(code);}
    private ApocBRPlayerMigrationVerification(){}
}
