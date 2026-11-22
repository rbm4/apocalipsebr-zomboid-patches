package zombie.apocalipse.humans.dialogue;

import java.util.*;

/** Pinned immutable graph and per-conversation cursor. No Lua, SQL, players or transport. */
public final class HumanDialogueSession {
    private final Map<String,Object> graph;
    private final Set<String> earned=new HashSet<>();
    private String node;
    private int sequence;
    private boolean completed;
    public record Choice(Map<String,Object> option,Map<String,Object> text,int sequence) { }
    public HumanDialogueSession(Map<String,Object> graph) {this.graph=graph;node=(String)graph.get("entryNode");}
    public String id(){return (String)graph.get("id");}
    public int revision(){return ((Number)graph.get("revision")).intValue();}
    public int sequence(){return sequence;}
    public String node(){return node;}
    public boolean completed(){return completed;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> options(Map<String,Object> node){return (List<Map<String,Object>>)node.get("options");}
    private Map<String,Object> node(String id){return map(map(graph.get("nodes")).get(id));}
    private boolean allowed(Map<String,Object> option,Set<String> flags) {
        return (!option.containsKey("requiredFlag")||flags.contains(option.get("requiredFlag")))
            &&(!option.containsKey("forbiddenFlag")||!flags.contains(option.get("forbiddenFlag")));
    }
    private Set<String> flags(Set<String> persisted){var result=new HashSet<>(persisted);result.addAll(earned);return result;}
    public Choice choose(String expectedNode,int expectedRevision,String option,int nextSequence,Set<String> persisted) {
        if(completed||!node.equals(expectedNode)||revision()!=expectedRevision||nextSequence!=sequence+1)return null;
        var flags=flags(persisted);
        for(var choice:options(node(node)))if(choice.get("id").equals(option)&&allowed(choice,flags))
            return new Choice(choice,HumanLanguage.resolve(map(choice.get("texts")),(String)graph.get("fallbackLocale")),nextSequence);
        return null;
    }
    /** Called only after actor validation, fact acceptance and speech execution succeeded. */
    public void advance(Choice choice) {
        if(choice.sequence()!=sequence+1)throw new IllegalStateException("Stale dialogue choice");
        Object flag=choice.option().get("setFlag");if(flag instanceof String text)earned.add(text);
        node=(String)choice.option().get("nextNode");sequence=choice.sequence();completed=Boolean.TRUE.equals(choice.option().get("close"));
    }
    private Map<String,Object> publicNode(String id,Set<String> flags) {
        var source=node(id);var result=new ArrayList<Map<String,Object>>();
        for(var choice:options(source))if(allowed(choice,flags)) {
            var text=HumanLanguage.resolve(map(choice.get("texts")),(String)graph.get("fallbackLocale"));
            result.add(Map.of("id",choice.get("id"),"title",text.get("title"),"description",text.get("description")));
        }
        return Map.of("id",id,"text",HumanLanguage.resolve(map(source.get("texts")),(String)graph.get("fallbackLocale")),"options",List.copyOf(result));
    }
    public Map<String,Object> view(Set<String> persisted) {
        var flags=flags(persisted);String root=(String)graph.get("entryNode");var nodes=new LinkedHashMap<String,Object>();
        nodes.put(node,publicNode(node,flags));if(!root.equals(node))nodes.put(root,publicNode(root,flags));
        return Map.of("schema",2,"id",id(),"revision",revision(),"entryNode",node,"rootNode",root,"locale",HumanLanguage.locale(),"labels",HumanLanguage.uiLabels(),"nodes",Map.copyOf(nodes));
    }
}
