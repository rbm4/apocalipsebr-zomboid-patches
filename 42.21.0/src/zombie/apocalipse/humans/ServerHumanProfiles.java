package zombie.apocalipse.humans;

import java.util.Map;
import zombie.core.Translator;
import zombie.apocalipse.humans.brain.*;
import zombie.apocalipse.humans.profiles.*;

/** Composition root for built-in profile registrations and domain content adapters. */
public final class ServerHumanProfiles {
    private static final HumanProfileRegistry REGISTRY = builtins();
    private ServerHumanProfiles() { }
    public static HumanProfileRegistry registry() { return REGISTRY; }
    private static HumanAnnouncementProvider announcements(HumanProfile p) {
        return switch (p.announcementId()) {
            case "none" -> HumanAnnouncementProvider.NONE;
            case "operations" -> new OperationAnnouncementProvider();
            default -> throw new IllegalArgumentException("Unknown announcement provider");
        };
    }
    private static HumanProfileRegistry builtins() {
        var registry = new HumanProfileRegistry();
        registry.registerBehavior("guide", p -> { GuidePolicy.from(p.parameters()); announcements(p); },
            p -> new GuideBrain(GuidePolicy.from(p.parameters()),
                () -> p.greetingKey().isEmpty() ? null : Translator.getText(p.greetingKey()), announcements(p)));
        registry.registerBehavior("idle", p -> {
            if (!p.parameters().isEmpty() || !p.greetingKey().isEmpty() || !p.announcementId().equals("none"))
                throw new IllegalArgumentException("Idle profile has no speech or timing configuration");
        }, p -> new IdleBehavior());
        registry.register(new HumanProfile("lobby-guide",1,"guide",Map.of(),"IGUI_ApocExtractionMapUtils_GuideHello","operations",true,true));
        registry.register(new HumanProfile("roaming-greeter",1,"guide",Map.of(),"IGUI_ApocExtractionMapUtils_GuideHello","none",true,true));
        registry.register(new HumanProfile("stationary",1,"idle",Map.of(),"","none",true,false));
        return registry;
    }
}
