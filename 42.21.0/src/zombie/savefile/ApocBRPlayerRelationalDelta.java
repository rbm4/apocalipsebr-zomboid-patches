package zombie.savefile;

import java.sql.*;
import java.util.*;

/** Worker-owned row comparison. Keys are pinned to V5, including its partial unique indexes. */
final class ApocBRPlayerRelationalDelta {
    static final Map<String,List<String>> KEYS;
    static {
        Map<String,List<String>> keys=new LinkedHashMap<>();
        add(keys,"id","object container item visual component");
        add(keys,"item_id","item_location bag_state weapon_state food_state clothing_state literature_state alarm_state key_state moveable_state map_state radio_state animal_track");
        add(keys,"character_id","character_state character_descriptor character_effects character_presentation character_vehicle_return character_nutrition body_damage thermoregulator human_visual");
        add(keys,"character_id,position","descriptor_extra character_recipe character_book_progress character_read_book character_print_media character_media_line fitness_body_part");
        add(keys,"item_id,position","item_extra food_spice literature_recipe map_symbol radio_preset");
        add(keys,"character_id,body_part_key","body_part thermal_node");
        add(keys,"character_id,kind,slot","equipment");
        add(keys,"character_id,stat_key","character_stat");
        add(keys,"character_id,trait_key","character_trait");
        add(keys,"character_id,perk_key","character_perk");
        add(keys,"character_id,title","character_literature");
        add(keys,"character_id,recipe_key","character_craft_history");
        add(keys,"character_id,native_item_key","character_mechanics");
        add(keys,"character_id,map_name,key","fitness_value");
        add(keys,"character_id,cheat_key","character_cheat");
        add(keys,"item_id,body_part_key","clothing_patch");
        add(keys,"item_id,page_key","literature_page");
        add(keys,"item_id,gene_key","egg_gene");
        add(keys,"item_id,gene_key,allele_index","egg_allele");
        add(keys,"component_id,id","value_node");
        add(keys,"character_id,visual_id,channel,body_part_key","visual_body_value");
        for(var table:ApocBRPlayerRelationalSchema.TABLES.entrySet())
            if(!Set.of("sync_issue","character_head").contains(table.getKey())&&table.getValue().stream().anyMatch(c->c.name().equals("snapshot_id"))&&!keys.containsKey(table.getKey()))
                throw new ExceptionInInitializerError("Missing mutation key: "+table.getKey());
        KEYS=Collections.unmodifiableMap(keys);
    }
    private static void add(Map<String,List<String>> keys,String columns,String tables){for(String table:tables.split(" "))keys.put(table,List.of(columns.split(",")));}
    record Key(String table,List<Object> values) {Key {values=Collections.unmodifiableList(new ArrayList<>(values));}}
    record Change(Key key,Map<String,Object> before,Map<String,Object> after,List<String> columns,boolean replace) {}
    static Key key(String table,Map<String,Object> row){return new Key(table,KEYS.get(table).stream().map(row::get).toList());}
    static Map<Key,Map<String,Object>> index(List<ApocBRPlayerRelationalCapture.Row> rows) {
        Map<Key,Map<String,Object>> result=new LinkedHashMap<>();
        for(var row:rows)if(result.put(key(row.table(),row.values()),row.values())!=null)throw new IllegalStateException("Duplicate mutation row");
        return result;
    }
    static List<Change> diff(Map<Key,Map<String,Object>> before,Map<Key,Map<String,Object>> after) {
        List<Change> changes=new ArrayList<>();Set<Key> keys=new LinkedHashSet<>(before.keySet());keys.addAll(after.keySet());
        for(Key key:keys) {
            var old=before.get(key);var row=after.get(key);List<String> columns=new ArrayList<>();
            if(old!=null&&row!=null)for(String column:row.keySet())if(!column.equals("snapshot_id")&&!Objects.deepEquals(old.get(column),row.get(column)))columns.add(column);
            if(old==null||row==null||!columns.isEmpty()) {
                // Delete changing unique-index entries before inserting any destinations (e.g. bag swaps).
                boolean replace=switch(key.table) {
                    case "item_location" -> columns.stream().anyMatch(Set.of("container_id","parent_item_id","position")::contains);
                    case "container" -> columns.stream().anyMatch(Set.of("owner_character_id","owner_item_id")::contains);
                    case "visual" -> columns.stream().anyMatch(Set.of("owner_character_id","owner_item_id","position")::contains);
                    case "bag_state" -> columns.contains("container_id");
                    case "value_node" -> columns.contains("parent_id")||columns.contains("position");
                    default -> false;
                };
                changes.add(new Change(key,old,row,List.copyOf(columns),replace));
            }
        }
        return changes;
    }
    static Map<Key,Map<String,Object>> load(Connection connection,UUID snapshot,long deadline)throws SQLException {
        Map<Key,Map<String,Object>> result=new LinkedHashMap<>();long bytes=0;
        for(String table:KEYS.keySet()) {
            var columns=ApocBRPlayerRelationalSchema.TABLES.get(table);
            try(PreparedStatement query=connection.prepareStatement("SELECT "+String.join(",",columns.stream().map(ApocBRPlayerRelationalSchema.Column::name).toList())+" FROM player_rel."+table+" WHERE snapshot_id=?")) {
                query.setQueryTimeout(ApocBRPlayerRelationalLimits.IMPORT_QUERY_SECONDS);query.setObject(1,snapshot);
                try(ResultSet rows=query.executeQuery()) {while(rows.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();bytes+=512;
                    for(int i=0;i<columns.size();i++) {
                        var column=columns.get(i);Object value=rows.getObject(i+1);
                        if(value instanceof Number n)value=switch(column.type()){case "real"->n.floatValue();case "double precision"->n.doubleValue();case "integer"->n.intValue();case "bigint"->n.longValue();default->value;};
                        row.put(column.name(),value);bytes+=64+(value instanceof String s?s.length()*2L:value instanceof byte[] b?b.length:0);
                    }
                    if(result.put(key(table,row),Collections.unmodifiableMap(row))!=null)throw new SQLException("Duplicate mutation baseline","21000");
                    if(bytes>ApocBRPlayerRelationalLimits.GRAPH_BYTES||result.size()>ApocBRPlayerRelationalLimits.ROWS)throw new SQLException("Mutation baseline size budget","54000");
                    checkTime(deadline);
                }}
            }
        }
        return result;
    }
    static void apply(Connection connection,UUID snapshot,List<Change> changes,long deadline)throws SQLException {
        // V5 foreign keys are deferred; its unique indexes are immediate. Free all changed locations first.
        run(connection,snapshot,changes,true,deadline);
        run(connection,snapshot,changes,false,deadline);
    }
    private static void run(Connection connection,UUID snapshot,List<Change> changes,boolean deleting,long deadline)throws SQLException {
        Map<String,PreparedStatement> statements=new LinkedHashMap<>();Map<String,Integer> pending=new HashMap<>();
        try {
            for(Change change:changes) {
                boolean remove=change.before!=null&&(change.after==null||change.replace);
                boolean create=change.after!=null&&(change.before==null||change.replace);
                if(deleting&&!remove||!deleting&&change.after==null)continue;
                String table=change.key.table;List<String> columns;
                String where=" WHERE snapshot_id=? AND "+String.join(" AND ",KEYS.get(table).stream().map(k->k+" IS NOT DISTINCT FROM ?").toList());
                String sql;
                if(deleting){columns=List.of();sql="DELETE FROM player_rel."+table+where;}
                else if(create){columns=ApocBRPlayerRelationalSchema.TABLES.get(table).stream().map(ApocBRPlayerRelationalSchema.Column::name).toList();sql="INSERT INTO player_rel."+table+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";}
                else {columns=change.columns;sql="UPDATE player_rel."+table+" SET "+String.join(",",columns.stream().map(c->c+"=?").toList())+where;}
                PreparedStatement statement=statements.get(sql);
                if(statement==null){
                    if(statements.size()>=128){for(var entry:statements.entrySet()){if(pending.getOrDefault(entry.getKey(),0)>0)execute(entry.getValue());entry.getValue().close();}statements.clear();pending.clear();checkTime(deadline);}
                    statement=connection.prepareStatement(sql);statement.setQueryTimeout(ApocBRPlayerRelationalLimits.IMPORT_QUERY_SECONDS);statements.put(sql,statement);}
                int parameter=1;
                for(String column:columns)statement.setObject(parameter++,column.equals("snapshot_id")?snapshot:change.after.get(column));
                if(deleting||!create){statement.setObject(parameter++,snapshot);for(Object value:change.key.values)statement.setObject(parameter++,value);}
                statement.addBatch();int count=pending.getOrDefault(sql,0)+1;
                if(count==128){execute(statement);count=0;}pending.put(sql,count);checkTime(deadline);
            }
            for(var entry:statements.entrySet())if(pending.get(entry.getKey())>0){execute(entry.getValue());checkTime(deadline);}
        } finally {for(PreparedStatement statement:statements.values())try{statement.close();}catch(SQLException ignored){}}
    }
    private static void execute(PreparedStatement statement)throws SQLException {for(int count:statement.executeBatch())if(count!=1&&count!=Statement.SUCCESS_NO_INFO)throw new SQLException("Missing mutation row","P0001");statement.clearBatch();}
    private static void checkTime(long deadline)throws SQLException {if(System.nanoTime()>deadline)throw new SQLException("Mutation time budget","57014");}
    private ApocBRPlayerRelationalDelta() {}
}
