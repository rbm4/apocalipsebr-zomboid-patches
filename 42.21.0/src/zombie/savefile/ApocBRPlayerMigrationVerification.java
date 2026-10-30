package zombie.savefile;

import java.util.*;
import java.util.function.Consumer;
import zombie.characters.IsoPlayer;

/** Read-only migration assertions. Native objects never escape the server-thread portion. */
final class ApocBRPlayerMigrationVerification {
    static final class AssertionFailure extends IllegalStateException {
        final String code;
        final String diagnostic;
        AssertionFailure(String code){this(code,"");}
        AssertionFailure(String code,String diagnostic){super(code);this.code=code;this.diagnostic=diagnostic;}
    }
    record Restored(ApocBRPlayerRelationalSnapshot.Snapshot snapshot,int payloadBytes,Set<String> skippedTraits) {}
    static Restored reconstruct(ApocBRPlayerRelationalReader.Profile profile,int slot,String username,
                                Consumer<String> stage,Consumer<String> passed,Consumer<String> diagnostic)throws Exception {
        try(var diagnostics=ApocBRPlayerReconstructionLog.collect(diagnostic)) {
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
        return new Restored(snapshot,bytes.length,Set.copyOf(diagnostics.skippedTraits));
        }
    }
    static void identity(ApocBRPlayerRelationalReader.Profile profile,UUID character,UUID snapshot,UUID dataset) {
        require(profile!=null,"PROFILE_MISSING");
        var metadata=profile.one("snapshot");
        require(character.toString().equals(String.valueOf(metadata.get("persisted_character_id"))),"CHARACTER_CHANGED");
        if(snapshot==null||!snapshot.toString().equals(String.valueOf(metadata.get("snapshot_id"))))
            throw new AssertionFailure("SNAPSHOT_CHANGED","; actualSnapshot="+ApocBRPlayerReconstructionLog.token(metadata.get("snapshot_id")));
        if(dataset!=null)require(dataset.toString().equals(String.valueOf(metadata.get("source_dataset_id"))),"SOURCE_DATASET_CHANGED");
    }
    static void compare(ApocBRPlayerRelationalReader.Profile source,ApocBRPlayerRelationalCapture.Graph graph,Consumer<String> passed) {
        compare(source,graph,Set.of(),passed);
    }
    static void compare(ApocBRPlayerRelationalReader.Profile source,ApocBRPlayerRelationalCapture.Graph graph,Set<String> skippedTraits,Consumer<String> passed) {
        Map<String,List<Map<String,Object>>> restored=new HashMap<>();
        for(var row:graph.rows())restored.computeIfAbsent(row.table(),key->new ArrayList<>()).add(row.values());
        // UUIDs become ordinals in the reader; compare counts and ownership through the normal reconstruction validator.
        for(String table:List.of("item","container","equipment","item_location")) {
            require(source.rows(table).size()==restored.getOrDefault(table,List.of()).size(),"ROW_COUNT_MISMATCH_"+table);
            passed.accept("COUNT_"+table);
        }
        var expectedEquipment=equipment(source.rows("item"),source.rows("equipment"));
        var actualEquipment=equipment(restored.getOrDefault("item",List.of()),restored.getOrDefault("equipment",List.of()));
        if(!expectedEquipment.equals(actualEquipment))throw new AssertionFailure("EQUIPMENT_TARGET_MISMATCH",equipmentDiagnostic(expectedEquipment,actualEquipment));
        passed.accept("EQUIPMENT_TARGETS");
        compareTable(source,restored,"item","native_id",List.of("full_type","current_uses","condition","current_ammo_count"));passed.accept("ITEM_STATE");
        // Stable natural keys allow values to be checked independently of UUID/ordinal allocation and row ordering.
        compareRows(source.rows("character_trait").stream().filter(row->!skippedTraits.contains(row.get("trait_key"))).toList(),
            restored.getOrDefault("character_trait",List.of()),"character_trait","trait_key",List.of("trait_key"));passed.accept("TRAITS");
        if(!skippedTraits.isEmpty())passed.accept("TRAITS_SKIPPED_MISSING="+skippedTraits.size());
        compareTable(source,restored,"character_stat","stat_key",List.of("value"));passed.accept("STATS");
        compareTable(source,restored,"character_perk","perk_key",List.of("xp","level","descriptor_xp_boost","multiplier","multiplier_min_level","multiplier_max_level"));passed.accept("PERKS_XP");
        compareTable(source,restored,"body_part","body_part_key",List.of("health","cut","bitten","scratched","bandaged","bleeding","deep_wounded","infected"));passed.accept("BODY_PART_HEALTH");
        compareTable(source,restored,"character_state",null,List.of("is_dead","hours_survived","zombie_kills","survivor_kills","total_xp","xp_level","xp_last_level"));passed.accept("CHARACTER_STATE");
    }
    private static Map<List<Object>,Long> equipment(List<Map<String,Object>> items,List<Map<String,Object>> equipment) {
        Map<String,Long> ids=new HashMap<>();
        for(var item:items)ids.put(String.valueOf(item.get("id")),((Number)item.get("native_id")).longValue());
        Map<List<Object>,Long> targets=new HashMap<>();
        List<Map<String,Object>> ordered=new ArrayList<>(equipment);
        ordered.sort(Comparator.comparingInt(row->((Number)row.get("position")).intValue()));
        Map<String,Integer> ranks=new HashMap<>();Set<List<Object>> positions=new HashSet<>(),slots=new HashSet<>();
        for(var row:ordered) {
            Long target=ids.get(String.valueOf(row.get("item_id")));require(target!=null,"EQUIPMENT_TARGET_MISSING");
            String kind=(String)row.get("kind");int position=((Number)row.get("position")).intValue();
            require(position>=0&&positions.add(List.of(kind,position)),"INVALID_EQUIPMENT_POSITION");
            require(slots.add(List.of(kind,row.get("slot"))),"DUPLICATE_EQUIPMENT_SLOT");
            // Preserve relative order within each equipment kind, but compact gaps left by skipped references.
            int normalized=kind.equals("hand")?position:ranks.merge(kind,1,Integer::sum)-1;
            var key=List.of(kind,row.get("slot"),normalized);
            require(targets.put(key,target)==null,"DUPLICATE_EQUIPMENT_SLOT");
        }
        return targets;
    }
    private static Map<List<Object>,Integer> equipmentTargets(Map<List<Object>,Long> equipment) {
        Map<List<Object>,Integer> targets=new HashMap<>();
        equipment.forEach((key,item)->targets.merge(List.of(key.get(0),key.get(1),item),1,Integer::sum));
        return targets;
    }
    private static String equipmentDiagnostic(Map<List<Object>,Long> expected,Map<List<Object>,Long> actual) {
        Set<List<Object>> keys=new HashSet<>(expected.keySet());keys.addAll(actual.keySet());
        List<List<Object>> differences=new ArrayList<>();
        for(var key:keys)if(!Objects.equals(expected.get(key),actual.get(key)))differences.add(key);
        differences.sort(Comparator.comparing((List<Object> key)->String.valueOf(key.get(0)))
            .thenComparing(key->String.valueOf(key.get(1))).thenComparingInt(key->((Number)key.get(2)).intValue()));
        StringBuilder detail=new StringBuilder("; equipmentPositionsOnly=")
            .append(equipmentTargets(expected).equals(equipmentTargets(actual)))
            .append("; equipmentDifferenceCount=").append(differences.size());
        // Only bounded identifiers/integers; never arbitrary item names, mod data or loader messages.
        for(int i=0;i<Math.min(8,differences.size());i++) {
            var key=differences.get(i);
            detail.append("; equipmentDiff").append(i).append("=")
                .append(ApocBRPlayerReconstructionLog.token(key.get(0))).append('/')
                .append(ApocBRPlayerReconstructionLog.token(key.get(1))).append('@').append(key.get(2))
                .append(":expectedNativeId=").append(expected.get(key)==null?"NONE":expected.get(key))
                .append(",actualNativeId=").append(actual.get(key)==null?"NONE":actual.get(key));
        }
        return detail.toString();
    }
    private static void compareTable(ApocBRPlayerRelationalReader.Profile source,Map<String,List<Map<String,Object>>> restored,
                                     String table,String key,List<String> fields) {
        compareRows(source.rows(table),restored.getOrDefault(table,List.of()),table,key,fields);
    }
    private static void compareRows(List<Map<String,Object>> expectedRows,List<Map<String,Object>> actualRows,String table,String key,List<String> fields) {
        var expected=index(expectedRows,key);var actual=index(actualRows,key);
        if(!expected.keySet().equals(actual.keySet()))throw new AssertionFailure("KEYS_MISMATCH_"+table,keyDiagnostic(expected.keySet(),actual.keySet()));
        for(var entry:expected.entrySet())for(String field:fields)
            require(equalValue(entry.getValue().get(field),actual.get(entry.getKey()).get(field)),"VALUE_MISMATCH_"+table+"."+field);
    }
    private static String keyDiagnostic(Set<Object> expected,Set<Object> actual) {
        Set<Object> missing=new HashSet<>(expected);missing.removeAll(actual);
        Set<Object> unexpected=new HashSet<>(actual);unexpected.removeAll(expected);
        return "; missingKeyCount="+missing.size()+"; unexpectedKeyCount="+unexpected.size()
            +"; missingKeys="+keySample(missing)+"; unexpectedKeys="+keySample(unexpected);
    }
    private static String keySample(Set<Object> keys) {
        return keys.stream().sorted(Comparator.comparing(String::valueOf)).limit(8)
            .map(key->key.getClass().getSimpleName()+":"+ApocBRPlayerReconstructionLog.token(key)).toList().toString();
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
