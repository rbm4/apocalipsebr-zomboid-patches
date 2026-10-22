package zombie.apocalipse.humans.dialogue;

import java.util.Locale;
import java.util.Map;

/** NPC-only language policy. Never changes the global game/client Translator. */
public final class HumanLanguage {
    private static final String LOCALE=System.getProperty("apocbr.npc.language","PTBR").trim().toUpperCase(Locale.ROOT).replace("-","");
    static {if(!LOCALE.matches("[A-Z_]{2,16}"))throw new IllegalArgumentException("Invalid NPC language");}
    private HumanLanguage() { }
    public static String locale(){return LOCALE;}
    private static final java.util.concurrent.atomic.AtomicBoolean fallbackLogged=new java.util.concurrent.atomic.AtomicBoolean();
    @SuppressWarnings("unchecked")
    public static Map<String,Object> resolve(Map<String,Object> translations,String fallback) {
        Object text=translations.get(LOCALE);
        String selected=LOCALE;
        if(text==null){text=translations.get("PTBR");selected="PTBR";}
        if(text==null){text=translations.get(fallback);selected=fallback;}
        if(!(text instanceof Map<?,?>))throw new IllegalArgumentException("NPC translation unavailable");
        if(!selected.equals(LOCALE)&&fallbackLogged.compareAndSet(false,true))
            zombie.debug.DebugLog.log("[ApocBR][NPC] translation fallback; requested="+LOCALE+"; using="+selected+"; further fallback messages suppressed");
        return (Map<String,Object>)text;
    }
    public static Map<String,Object> uiLabels() {
        return Map.of("talk",text("IGUI_ApocExtractionMapUtils_DialogTalk"),"close",text("IGUI_ApocExtractionMapUtils_DialogClose"));
    }
    public static String text(String key,Object...args) {
        var catalog=HumanDialogueCatalog.current();
        Map<String,Object> translations=catalog==null?null:catalog.labels().get(key);
        if(translations==null)translations=DEFAULTS.get(key);
        if(translations==null)return null;
        String result=(String)resolve(translations,"EN").get("text");
        // Replace placeholders in one pass so inserted user content is never interpreted again.
        var matcher=java.util.regex.Pattern.compile("%([1-9])").matcher(result);var output=new StringBuilder();
        while(matcher.find()){int index=Integer.parseInt(matcher.group(1))-1;
            matcher.appendReplacement(output,java.util.regex.Matcher.quoteReplacement(index<args.length?String.valueOf(args[index]):matcher.group()));}
        matcher.appendTail(output);return output.toString();
    }
    // Standalone operation without SQL uses the same shipped labels as the migration.
    private static final Map<String,Map<String,Object>> DEFAULTS=Map.ofEntries(
        Map.entry("IGUI_ApocExtractionMapUtils_GuideHello",Map.of("PTBR",Map.of("text","Olá!"),"EN",Map.of("text","Hello!"))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventCountdown",Map.of("PTBR",Map.of("text","A próxima operação, %1, começa em %2."),"EN",Map.of("text","The next operation, %1, starts in %2."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventRemaining",Map.of("PTBR",Map.of("text","A operação %1 está ativa. Tempo restante estimado: %2."),"EN",Map.of("text","Operation %1 is active. Estimated time remaining: %2."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventNone",Map.of("PTBR",Map.of("text","Não há próximas operações agendadas no momento."),"EN",Map.of("text","No upcoming operations are currently scheduled."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventScheduled",Map.of("PTBR",Map.of("text","A próxima operação, %1, começa em %2."),"EN",Map.of("text","The next operation, %1, starts in %2."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventPending",Map.of("PTBR",Map.of("text","A operação %1 está aguardando a liberação do perímetro do lobby."),"EN",Map.of("text","Operation %1 is waiting for the lobby perimeter to open."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventReleased",Map.of("PTBR",Map.of("text","A operação %1 começou. O perímetro do lobby está sendo liberado."),"EN",Map.of("text","Operation %1 has started. The lobby perimeter is opening."))),
        Map.entry("IGUI_ApocExtractionMapUtils_EventUnavailable",Map.of("PTBR",Map.of("text","A agenda de operações está temporariamente indisponível."),"EN",Map.of("text","The operation schedule is temporarily unavailable."))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogTalk",Map.of("PTBR",Map.of("text","Conversar com %1"),"EN",Map.of("text","Talk to %1"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogClose",Map.of("PTBR",Map.of("text","Encerrar conversa"),"EN",Map.of("text","End conversation"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogGreeting",Map.of("PTBR",Map.of("text","Olá! Como posso ajudar?"),"EN",Map.of("text","Hello! How can I help?"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogHello",Map.of("PTBR",Map.of("text","Olá! Tudo bem?"),"EN",Map.of("text","Hello! How are you?"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogHelloReply",Map.of("PTBR",Map.of("text","Olá, sobrevivente! Que bom ver você por aqui."),"EN",Map.of("text","Hello, survivor! Good to see you here."))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogHelp",Map.of("PTBR",Map.of("text","Como funciona este lugar?"),"EN",Map.of("text","How does this place work?"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogHelpReply",Map.of("PTBR",Map.of("text","Este é o nosso ponto de encontro. Prepare seu equipamento e fique atento aos avisos das operações."),"EN",Map.of("text","This is our meeting point. Get your gear ready and watch for operation announcements."))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogThanks",Map.of("PTBR",Map.of("text","Obrigado pela ajuda!"),"EN",Map.of("text","Thanks for your help!"))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogThanksReply",Map.of("PTBR",Map.of("text","Disponha! Boa sorte lá fora."),"EN",Map.of("text","You're welcome! Good luck out there."))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogBusy",Map.of("PTBR",Map.of("text","Estou atendendo outra pessoa. Aguarde um instante."),"EN",Map.of("text","I'm helping someone else. Please wait a moment."))),
        Map.entry("IGUI_ApocExtractionMapUtils_DialogUnavailable",Map.of("PTBR",Map.of("text","Não foi possível conversar agora. Aproxime-se e tente novamente."),"EN",Map.of("text","Unable to start a conversation right now. Move closer and try again.")))
    );
}
