package zombie.apocalipse.humans;

import java.util.Map;
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
        modules.register("group-patrol",(p,b)->{
            if(!b.parameters().isEmpty()||p.modules().stream().anyMatch(other->other.moduleId().equals("walk")))throw new IllegalArgumentException("Group patrol replaces background walk scheduling");
            var pattern=p.walkingPatterns().stream().filter(s->s.slot().equals("background")).findFirst().orElseThrow();
            if(!pattern.patternId().equals("waypoint-circuit")||pattern.area()==null)throw new IllegalArgumentException("Group patrol requires a bounded waypoint circuit");
            if(p.modules().stream().anyMatch(other->!other.moduleId().equals("group-patrol")&&other.priority()>=b.priority()))throw new IllegalArgumentException("Group patrol must follow defensive/medical modules");
        },(p,b)->new zombie.apocalipse.humans.groups.GroupPatrolModule());
        modules.register("healing",(p,b)->{
            var settings=zombie.apocalipse.humans.healing.HealingSettings.from(b.parameters());
            if(settings.clearTiles()>p.perceptionRadius())throw new IllegalArgumentException("Healing clearance must fit perception radius");
            if(p.modules().stream().anyMatch(other->(other.moduleId().equals("survival")||other.moduleId().equals("retaliate-player"))&&other.priority()>=b.priority()
                ||!(other.moduleId().equals("survival")||other.moduleId().equals("retaliate-player")||other.moduleId().equals("healing"))&&other.priority()<=b.priority()))
                throw new IllegalArgumentException("Healing must follow survival/retaliation and precede routine activity/combat");
        },(p,b)->new zombie.apocalipse.humans.healing.HealingModule(zombie.apocalipse.humans.healing.HealingSettings.from(b.parameters())));
        modules.register("greet",(p,b)->{GuidePolicy.from(b.parameters());if(b.parameters().containsKey("roamPauseMs"))throw new IllegalArgumentException("Roam timing belongs to roam module");announcements(p);},
            (p,b)->new GuideBrain(GuidePolicy.from(b.parameters()),new zombie.apocalipse.humans.dialogue.HumanGreetingProvider(p.greetingKey()),announcements(p)).greetingOnly());
        modules.register("walk",(p,b)->{if(b.parameters().keySet().stream().anyMatch(k->!k.equals("pauseMs")))throw new IllegalArgumentException("Unknown walking parameter");if(p.walkingPatterns().stream().noneMatch(s->s.slot().equals("background")))throw new IllegalArgumentException("Background walking pattern required");},
            (p,b)->new zombie.apocalipse.humans.brain.WalkingModule(b.parameters().getOrDefault("pauseMs",3000L)*1000000L));
        modules.register("engage-zombies",(p,b)->HumanEngagement.Settings.fromMilliseconds(b.parameters()),
            (p,b)->new EngageZombiesModule(HumanEngagement.Settings.fromMilliseconds(b.parameters())));
        modules.register("retaliate-player",(p,b)->{
            zombie.apocalipse.humans.encounters.RetaliatePlayerModule.Settings.from(b.parameters());
            if(p.modules().stream().anyMatch(other->other.moduleId().equals("survival")?other.priority()>=b.priority():
                !other.moduleId().equals("retaliate-player")&&other.priority()<=b.priority()))throw new IllegalArgumentException("Retaliation must follow survival and precede other modules");
        },(p,b)->new zombie.apocalipse.humans.encounters.RetaliatePlayerModule(zombie.apocalipse.humans.encounters.RetaliatePlayerModule.Settings.from(b.parameters())));
        modules.register("assigned-task",(p,b)->{
            if(!b.parameters().isEmpty())throw new IllegalArgumentException("Tasks use per-command typed parameters");
            if(p.walkingPatterns().stream().noneMatch(s->s.slot().equals("background")))throw new IllegalArgumentException("Tasks require a movement scope");
            if(p.modules().stream().anyMatch(other->(other.moduleId().equals("survival")||other.moduleId().equals("engage-zombies"))&&other.priority()>=b.priority()
                ||other.moduleId().equals("walk")&&other.priority()<=b.priority()))throw new IllegalArgumentException("Tasks must follow combat and precede background walking");
        },(p,b)->new zombie.apocalipse.humans.tasks.AssignedTaskModule());
        modules.register("player-encounter",(p,b)->{
            if(!p.encounter().enabled()||!b.parameters().isEmpty()||p.encounter().challengeTiles()>p.perceptionRadius())
                throw new IllegalArgumentException("Typed encounter policy within perception required");
            if(p.modules().stream().anyMatch(other->((other.moduleId().equals("survival")||other.moduleId().equals("engage-zombies"))&&other.priority()>=b.priority())
                ||((other.moduleId().equals("walk")||other.moduleId().equals("assigned-task")||other.moduleId().equals("greet"))&&other.priority()<=b.priority())))
                throw new IllegalArgumentException("Encounters must follow defense and precede ordinary activity");
        },(p,b)->new zombie.apocalipse.humans.encounters.PlayerEncounterModule(p.encounter()));
        modules.register("survival",(p,b)->{
            if(!b.parameters().isEmpty())throw new IllegalArgumentException("Survival uses typed profile settings");
            if(p.walkingPatterns().stream().noneMatch(s->s.slot().equals("background")))throw new IllegalArgumentException("Survival needs a background movement scope");
            if(p.modules().stream().anyMatch(other->!other.moduleId().equals("survival")&&other.priority()<=b.priority()))
                throw new IllegalArgumentException("Survival must precede all autonomous modules");
        },(p,b)->new zombie.apocalipse.humans.survival.SurvivalModule(p.survival()));
        registry.registerBehavior("modules",p->{
            announcements(p);modules.validate(p);
            if(p.encounter().enabled()!=p.modules().stream().anyMatch(b->b.moduleId().equals("player-encounter")))
                throw new IllegalArgumentException("Encounter policy and module must be bound together");
        },modules::create);
        registry.registerBehavior("guide", p -> { GuidePolicy.from(p.parameters()); announcements(p); },
            p -> new GuideBrain(GuidePolicy.from(p.parameters()),
                new zombie.apocalipse.humans.dialogue.HumanGreetingProvider(p.greetingKey()), announcements(p)));
        registry.registerBehavior("idle", p -> {
            if (!p.parameters().isEmpty() || !p.greetingKey().isEmpty() || !p.announcementId().equals("none"))
                throw new IllegalArgumentException("Idle profile has no speech or timing configuration");
        }, p -> new IdleBehavior());
        registry.register(modular("lobby-guide","operations",true));
        registry.register(modular("roaming-greeter","none",true));
        registry.register(new HumanProfile("stationary",1,"idle",Map.of(),"","none",true,false));
        registry.register(new HumanProfile("militia-patrol",1,"modules",Map.of(),"","none",false,false,
            java.util.List.of(new HumanModuleBinding("survival",10,100,Map.of()),new HumanModuleBinding("retaliate-player",11,100,Map.of()),new HumanModuleBinding("engage-zombies",100,100,Map.of()),
                new HumanModuleBinding("walk",1000,100,Map.of())),8,100,true));
        return registry;
    }
}
