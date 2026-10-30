package zombie.apocalipse.humans.profiles;

import java.util.Map;
import java.util.Objects;

/** Persistence-ready configuration data; contains no Java objects, timers or world references. */
public record HumanProfile(String id, int revision, String behaviorId, Map<String, Long> parameters,
                           String greetingKey, String announcementId, boolean dialogue, boolean trading, java.util.List<HumanModuleBinding> modules,
                           double perceptionRadius, int perceptionIntervalMs, boolean requireLos,
                           java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec> walkingPatterns,
                           HumanCheats cheats,zombie.apocalipse.humans.survival.SurvivalSettings survival,
                           zombie.apocalipse.humans.encounters.EncounterPolicy encounter) {
    public HumanProfile(String id,int revision,String behaviorId,Map<String,Long> parameters,String greetingKey,String announcementId,boolean dialogue,boolean trading,
                        java.util.List<HumanModuleBinding> modules,double perceptionRadius,int perceptionIntervalMs,boolean requireLos,
                        java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec> walkingPatterns,HumanCheats cheats,zombie.apocalipse.humans.survival.SurvivalSettings survival) {
        this(id,revision,behaviorId,parameters,greetingKey,announcementId,dialogue,trading,modules,perceptionRadius,perceptionIntervalMs,requireLos,walkingPatterns,cheats,survival,
            zombie.apocalipse.humans.encounters.EncounterPolicy.DISABLED);
    }
    public HumanProfile(String id,int revision,String behaviorId,Map<String,Long> parameters,String greetingKey,String announcementId,boolean dialogue,boolean trading,
                        java.util.List<HumanModuleBinding> modules,double perceptionRadius,int perceptionIntervalMs,boolean requireLos,
                        java.util.List<zombie.apocalipse.humans.walking.WalkingPatternSpec> walkingPatterns) {
        this(id,revision,behaviorId,parameters,greetingKey,announcementId,dialogue,trading,modules,perceptionRadius,perceptionIntervalMs,requireLos,
            walkingPatterns,HumanCheats.DEFAULT,zombie.apocalipse.humans.survival.SurvivalSettings.DEFAULT);
    }
    public HumanProfile(String id,int revision,String behaviorId,Map<String,Long> parameters,String greetingKey,String announcementId,boolean dialogue,boolean trading,
                        java.util.List<HumanModuleBinding> modules,double perceptionRadius,int perceptionIntervalMs,boolean requireLos) {
        this(id,revision,behaviorId,parameters,greetingKey,announcementId,dialogue,trading,modules,perceptionRadius,perceptionIntervalMs,requireLos,
            java.util.List.of(zombie.apocalipse.humans.walking.WalkingPatternSpec.indoor()));
    }
    public HumanProfile(String id,int revision,String behaviorId,Map<String,Long> parameters,String greetingKey,String announcementId,boolean dialogue,boolean trading) {
        this(id,revision,behaviorId,parameters,greetingKey,announcementId,dialogue,trading,java.util.List.of(),8,100,true);
    }
    public HumanProfile {
        Objects.requireNonNull(cheats);Objects.requireNonNull(survival);Objects.requireNonNull(encounter);
        identifier(id); identifier(behaviorId); identifier(announcementId);
        walkingPatterns=java.util.List.copyOf(walkingPatterns);
        if(walkingPatterns.size()>8||walkingPatterns.stream().map(zombie.apocalipse.humans.walking.WalkingPatternSpec::slot).distinct().count()!=walkingPatterns.size())
            throw new IllegalArgumentException("Invalid movement pattern slots");
        modules=java.util.List.copyOf(modules);
        if(modules.size()>16||modules.stream().map(HumanModuleBinding::moduleId).distinct().count()!=modules.size())throw new IllegalArgumentException("Invalid module count/duplicates");
        if(!Double.isFinite(perceptionRadius)||perceptionRadius<1||perceptionRadius>32||perceptionIntervalMs<100||perceptionIntervalMs>60000)throw new IllegalArgumentException("Invalid perception");
        if (revision < 1) throw new IllegalArgumentException("Invalid profile revision");
        parameters = Map.copyOf(parameters);
        if (parameters.size() > 16) throw new IllegalArgumentException("Too many profile parameters");
        for (var entry : parameters.entrySet()) {
            identifier(entry.getKey());
            if (entry.getValue() < 0 || entry.getValue() > 86400000L) throw new IllegalArgumentException("Invalid parameter milliseconds");
        }
        Objects.requireNonNull(greetingKey);
        if (greetingKey.length() > 128) throw new IllegalArgumentException("Greeting key too long");
    }
    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Invalid profile identifier");
    }
}
