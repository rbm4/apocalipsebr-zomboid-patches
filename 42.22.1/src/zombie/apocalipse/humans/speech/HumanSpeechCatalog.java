package zombie.apocalipse.humans.speech;

import java.util.*;

/** Immutable validated content. Workers publish whole snapshots; combat never loads content. */
public final class HumanSpeechCatalog {
    public record Rule(int chance,long cooldownMs,int priority) {
        public Rule {if(chance<0||chance>100||cooldownMs<0||cooldownMs>86400000||priority<0||priority>100)throw new IllegalArgumentException("Invalid speech rule");}
    }
    public record Line(String id,SpeechEvent event,String locale,String text,int weight,float minHealth,float maxHealth,
                       SpeechContext.AttackKind attack,String temperament,int minAggressors) {
        public Line {
            if(id==null||!id.matches("[A-Za-z0-9_.-]{1,128}")||event==null||!("EN".equals(locale)||"PTBR".equals(locale))
                ||text==null||text.isBlank()||text.length()>256||weight<1||weight>10000||!Float.isFinite(minHealth)||!Float.isFinite(maxHealth)
                ||minHealth<0||maxHealth>1||minHealth>maxHealth||attack==null||temperament==null
                ||!(temperament.equals("ANY")||Arrays.stream(SpeechContext.Temperament.values()).anyMatch(t->t.name().equals(temperament)))
                ||minAggressors<0||minAggressors>128||text.replace("{username}","").matches("(?s).*[{}].*"))
                throw new IllegalArgumentException("Invalid speech line");
        }
        public boolean fits(SpeechContext c){return c.healthRatio()>=minHealth&&c.healthRatio()<=maxHealth
            &&(attack==SpeechContext.AttackKind.ANY||attack==c.attack())&&(temperament.equals("ANY")||temperament.equals(c.temperament().name()))
            &&c.aggressors()>=minAggressors&&(!text.contains("{username}")||!c.safeUsername().isBlank());}
    }
    private final Map<SpeechEvent,Rule> rules;
    private final Map<String,List<Line>> pools;
    public HumanSpeechCatalog(Map<SpeechEvent,Rule> rules,List<Line> lines){
        this.rules=Map.copyOf(rules);
        if(lines.size()>4096||rules.size()>SpeechEvent.values().length)throw new IllegalArgumentException("Speech catalog too large");
        var grouped=new HashMap<String,List<Line>>();var ids=new HashSet<String>();
        for(var line:lines){
            if(!ids.add(line.id()+":"+line.locale()))throw new IllegalArgumentException("Duplicate speech line");
            grouped.computeIfAbsent(line.event()+":"+line.locale(),k->new ArrayList<>()).add(line);
        }
        var copy=new HashMap<String,List<Line>>();grouped.forEach((k,v)->copy.put(k,List.copyOf(v)));pools=Map.copyOf(copy);
    }
    public Rule rule(SpeechEvent event){return rules.getOrDefault(event,rules.getOrDefault(event.pool(),new Rule(0,6000,10)));}
    public List<Line> lines(SpeechEvent event,String locale){
        var exact=pools.get(event+":"+locale);
        if(exact==null)exact=pools.get(event.pool()+":"+locale);
        if(exact==null&&!"EN".equals(locale))return lines(event,"EN");
        return exact==null?List.of():exact;
    }
    private static volatile HumanSpeechCatalog current=HumanSpeechDefaults.catalog();
    public static HumanSpeechCatalog current(){return current;}
    public static void publish(HumanSpeechCatalog value){current=Objects.requireNonNull(value);}
}
