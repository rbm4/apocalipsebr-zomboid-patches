package zombie.apocalipse.humans.dialogue;

import java.util.*;
import java.util.function.IntUnaryOperator;

/** Bounded per-conversation pool memory. Data-only; no Lua, world reads or delivery. */
public final class HumanDialogueResponses {
    private final Map<String,String> previous = new HashMap<>();
    private final IntUnaryOperator choose;
    public HumanDialogueResponses() { this(n -> java.util.concurrent.ThreadLocalRandom.current().nextInt(n)); }
    public HumanDialogueResponses(IntUnaryOperator choose) { this.choose=Objects.requireNonNull(choose); }
    public String resolve(String prefix,String pool,Map<String,String> values) {
        if(pool==null||!pool.startsWith(prefix+".")||!pool.matches("[A-Za-z0-9_.-]{1,100}")||values.size()>16)
            throw new IllegalArgumentException("Invalid response pool");
        var snapshot=HumanDialogueCatalog.current();
        if(snapshot==null)throw new IllegalArgumentException("Response catalog unavailable");
        var candidates=new ArrayList<String>();
        for(int i=1;i<=32;i++) {
            var translations=snapshot.labels().get(pool+"."+i);
            if(translations==null)continue;
            String text=(String)HumanLanguage.resolve(translations,"EN").get("text");
            if(text==null||text.isBlank()||text.length()>256)throw new IllegalArgumentException("Invalid response text");
            if(!candidates.contains(text))candidates.add(text);
        }
        if(candidates.size()>1)candidates.remove(previous.get(pool));
        if(candidates.isEmpty())throw new IllegalArgumentException("Response pool unavailable");
        String template=candidates.get(choose.applyAsInt(candidates.size()));
        var matcher=java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,31})\\}").matcher(template);
        var output=new StringBuilder();
        while(matcher.find()) {
            String value=values.get(matcher.group(1));
            if(value==null||value.length()>64||value.chars().anyMatch(c->c<32||c=='<'||c=='>'))
                throw new IllegalArgumentException("Invalid response placeholder");
            matcher.appendReplacement(output,java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(output);
        if(output.length()>256)throw new IllegalArgumentException("Rendered response too long");
        if(previous.size()<16||previous.containsKey(pool))previous.put(pool,template);
        return output.toString();
    }
}
