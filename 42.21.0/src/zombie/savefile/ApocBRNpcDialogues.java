package zombie.savefile;

import java.sql.*;
import java.util.*;

/** JDBC content adapter. Called only inside the NPC worker's repeatable-read transaction. */
public final class ApocBRNpcDialogues {
    private ApocBRNpcDialogues() { }
    public static Map<String,Object> read(Connection c) throws SQLException {
        Map<String,Object> profiles = new LinkedHashMap<>();
        Map<String,Object> graphs = new HashMap<>();
        try (var s = c.prepareStatement("""
            SELECT b.profile_id,d.dialogue_id,d.current_revision,r.entry_node,r.fallback_locale
            FROM npc_engine.profile_dialogue b JOIN npc_engine.dialogue d USING(dialogue_id)
            JOIN npc_engine.dialogue_revision r ON r.dialogue_id=d.dialogue_id AND r.revision=d.current_revision
            ORDER BY b.profile_id LIMIT 257
            """)) {
            s.setQueryTimeout(2);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    if (profiles.size() >= 256) throw new SQLException("Dialogue binding limit", "22023");
                    String id=rows.getString(2); int revision=rows.getInt(3);
                    String cacheKey=id+":"+revision;
                    Object graph=graphs.get(cacheKey);
                    if (graph==null) {
                        graph=graph(c,id,revision,rows.getString(4),rows.getString(5));
                        graphs.put(cacheKey,graph);
                    }
                    profiles.put(rows.getString(1),graph);
                }
            }
        }
        return Map.copyOf(profiles);
    }
    private static Map<String,Object> graph(Connection c,String id,int revision,String entry,String fallback) throws SQLException {
        Map<String,Object> nodes=new LinkedHashMap<>();
        int choices=0,bytes=0;
        Set<String> locales=new HashSet<>();
        try(var s=query(c,"SELECT node_id FROM npc_engine.dialogue_node WHERE dialogue_id=? AND revision=? ORDER BY node_id LIMIT 33",id,revision);var r=s.executeQuery()) {
            while(r.next()) {
                if(nodes.size()>=32)throw new SQLException("Dialogue node limit","22023");
                String node=r.getString(1);
                Map<String,Object> text=new LinkedHashMap<>();
                try(var t=query(c,"SELECT locale,title,description FROM npc_engine.dialogue_node_text WHERE dialogue_id=? AND revision=? AND node_id=? ORDER BY locale LIMIT 5",id,revision,node);var tr=t.executeQuery()) {
                    while(tr.next()) {
                        String locale=tr.getString(1);locales.add(locale);
                        text.put(locale,Map.of("title",tr.getString(2),"description",tr.getString(3)));
                        bytes+=tr.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+tr.getString(3).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    }
                }
                List<Object> options=new ArrayList<>();
                try(var o=query(c,"SELECT choice_id,next_node,required_flag,forbidden_flag,set_flag,emote,close_dialogue FROM npc_engine.dialogue_choice WHERE dialogue_id=? AND revision=? AND node_id=? ORDER BY sort_order,choice_id LIMIT 13",id,revision,node);var cr=o.executeQuery()) {
                    while(cr.next()) {
                        if(options.size()>=12 || ++choices>128)throw new SQLException("Dialogue choice limit","22023");
                        Map<String,Object> option=new LinkedHashMap<>();
                        option.put("id",cr.getString(1));option.put("nextNode",cr.getString(2));
                        for(int i=3;i<=6;i++)if(cr.getString(i)!=null)option.put(new String[]{"requiredFlag","forbiddenFlag","setFlag","emote"}[i-3],cr.getString(i));
                        option.put("close",cr.getBoolean(7));
                        Map<String,Object> translations=new LinkedHashMap<>();
                        try(var t=query(c,"SELECT locale,title,description,response,speech FROM npc_engine.dialogue_choice_text WHERE dialogue_id=? AND revision=? AND choice_id=? ORDER BY locale LIMIT 5",id,revision,cr.getString(1));var tr=t.executeQuery()) {
                            while(tr.next()) {
                                String locale=tr.getString(1);locales.add(locale);
                                translations.put(locale,Map.of("title",tr.getString(2),"description",tr.getString(3),"response",tr.getString(4),"speech",tr.getString(5)));
                                for(int i=2;i<=5;i++)bytes+=tr.getString(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                                if(tr.getString(5).length()>256)throw new SQLException("Dialogue speech exceeds engine limit","22023");
                            }
                        }
                        if(!translations.containsKey(fallback))throw new SQLException("Dialogue fallback missing","22023");
                        option.put("texts",Map.copyOf(translations));options.add(Map.copyOf(option));
                    }
                }
                if(!text.containsKey(fallback))throw new SQLException("Dialogue node fallback missing","22023");
                nodes.put(node,Map.of("id",node,"texts",Map.copyOf(text),"options",List.copyOf(options)));
            }
        }
        if(!nodes.containsKey(entry)||locales.size()>4||bytes>262144)throw new SQLException("Invalid/oversized dialogue","22023");
        return Map.of("id",id,"revision",revision,"entryNode",entry,"fallbackLocale",fallback,"nodes",Map.copyOf(nodes));
    }
    private static PreparedStatement query(Connection c,String sql,Object...args) throws SQLException {
        var s=c.prepareStatement(sql);
        try {s.setQueryTimeout(2);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
        catch(SQLException failure){s.close();throw failure;}
    }
}
