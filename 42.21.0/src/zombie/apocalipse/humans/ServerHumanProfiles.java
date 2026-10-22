package zombie.apocalipse.humans;

import java.util.Map;
import zombie.apocalipse.humans.dialogue.HumanLanguage;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.profiles.*;

/** Composition root for built-in profile registrations and domain content adapters. */
public final class ServerHumanProfiles {
    private static final zombie.apocalipse.humans.walking.WalkingPatternRegistry WALKING = zombie.apocalipse.humans.walking.WalkingPatternRegistry.builtins();
    private static final HumanProfileRegistry REGISTRY = builtins();
    private ServerHumanProfiles() { }
    public static HumanProfileRegistry registry() { return REGISTRY; }
    public static zombie.apocalipse.humans.walking.WalkingPatternRegistry walkingPatterns() { return WALKING; }
    private static HumanAnnouncementProvider announcements(HumanProfile p) {
        return switch (p.announcementId()) {
            case "none" -> HumanAnnouncementProvider.NONE;
            case "operations" -> new OperationAnnouncementProvider();
            default -> throw new IllegalArgumentException("Unknown announcement provider");
        };
    }
    private static HumanProfile modular(String id,String announcements,boolean trade) {
        return new HumanProfile(id,1,"modules",Map.of(),"IGUI_ApocExtractionMapUtils_GuideHello",announcements,true,trade,
            java.util.List.of(new zombie.apocalipse.humans.profiles.HumanModuleBinding("greet",100,100,Map.of()),
                new zombie.apocalipse.humans.profiles.HumanModuleBinding("walk",1000,100,Map.of())),8,100,true);
    }
    private static HumanProfileRegistry builtins() {
        var registry = new HumanProfileRegistry(WALKING);
        var modules=new zombie.apocalipse.humans.profiles.HumanModuleRegistry();
        modules.register("greet",(p,b)->{GuidePolicy.from(b.parameters());if(b.parameters().containsKey("roamPauseMs"))throw new IllegalArgumentException("Roam timing belongs to roam module");announcements(p);},
            (p,b)->new GuideBrain(GuidePolicy.from(b.parameters()),()->p.greetingKey().isEmpty()?null:HumanLanguage.text(p.greetingKey()),announcements(p)).greetingOnly());
        modules.register("walk",(p,b)->{if(b.parameters().keySet().stream().anyMatch(k->!k.equals("pauseMs")))throw new IllegalArgumentException("Unknown walking parameter");if(p.walkingPatterns().stream().noneMatch(s->s.slot().equals("background")))throw new IllegalArgumentException("Background walking pattern required");},
            (p,b)->new zombie.apocalipse.humans.brain.WalkingModule(b.parameters().getOrDefault("pauseMs",3000L)*1000000L));
        modules.register("engage-zombies",(p,b)->HumanEngagement.Settings.fromMilliseconds(b.parameters()),
            (p,b)->new EngageZombiesModule(HumanEngagement.Settings.fromMilliseconds(b.parameters())));
        modules.register("survival",(p,b)->{
            if(!b.parameters().isEmpty())throw new IllegalArgumentException("Survival uses typed profile settings");
            if(p.walkingPatterns().stream().noneMatch(s->s.slot().equals("background")))throw new IllegalArgumentException("Survival needs a background movement scope");
            if(p.modules().stream().anyMatch(other->!other.moduleId().equals("survival")&&other.priority()<=b.priority()))
                throw new IllegalArgumentException("Survival must precede all autonomous modules");
        },(p,b)->new zombie.apocalipse.humans.survival.SurvivalModule(p.survival()));
        registry.registerBehavior("modules",p->{announcements(p);modules.validate(p);},modules::create);
        registry.registerBehavior("guide", p -> { GuidePolicy.from(p.parameters()); announcements(p); },
            p -> new GuideBrain(GuidePolicy.from(p.parameters()),
                () -> p.greetingKey().isEmpty() ? null : HumanLanguage.text(p.greetingKey()), announcements(p)));
        registry.registerBehavior("idle", p -> {
            if (!p.parameters().isEmpty() || !p.greetingKey().isEmpty() || !p.announcementId().equals("none"))
                throw new IllegalArgumentException("Idle profile has no speech or timing configuration");
        }, p -> new IdleBehavior());
        registry.register(modular("lobby-guide","operations",true));
        registry.register(modular("roaming-greeter","none",true));
        registry.register(new HumanProfile("stationary",1,"idle",Map.of(),"","none",true,false));
        registry.register(new HumanProfile("militia-patrol",1,"modules",Map.of(),"","none",false,false,
            java.util.List.of(new HumanModuleBinding("survival",10,100,Map.of()),new HumanModuleBinding("engage-zombies",100,100,Map.of()),
                new HumanModuleBinding("walk",1000,100,Map.of())),8,100,true));
        return registry;
    }
}
