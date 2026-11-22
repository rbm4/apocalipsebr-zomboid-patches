package cloud.apocalipse.pz.agent.features.npc;

import cloud.apocalipse.pz.agent.config.AgentConfiguration;

import java.util.Locale;
import java.util.Objects;
import zombie.iso.IsoDirections;

/** Immutable input to the factory; contains no connection or database identity. */
public record ServerHumanDefinition(
    String key, String username, String displayName, float x, float y, int z,
    IsoDirections facing, boolean female, String outfit, String profileId,
    cloud.apocalipse.pz.agent.features.npc.death.HumanDeathPolicy deathPolicy,
    cloud.apocalipse.pz.agent.features.npc.groups.HumanGroupMembership group
) {
    public ServerHumanDefinition(String key,String username,String displayName,float x,float y,int z,IsoDirections facing,boolean female,String outfit,String profileId,cloud.apocalipse.pz.agent.features.npc.death.HumanDeathPolicy deathPolicy){
        this(key,username,displayName,x,y,z,facing,female,outfit,profileId,deathPolicy,cloud.apocalipse.pz.agent.features.npc.groups.HumanGroupMembership.NONE);
    }
    public ServerHumanDefinition(String key,String username,String displayName,float x,float y,int z,IsoDirections facing,boolean female,String outfit,String profileId){
        this(key,username,displayName,x,y,z,facing,female,outfit,profileId,cloud.apocalipse.pz.agent.features.npc.death.HumanDeathPolicy.DEFAULT);
    }
    /** Compatibility input selects an explicit generic profile, independently of actor key. */
    public ServerHumanDefinition(String key, String username, String displayName, float x, float y, int z,
                                 IsoDirections facing, boolean female, String outfit) {
        this(key,username,displayName,x,y,z,facing,female,outfit,"roaming-greeter");
    }
    public ServerHumanDefinition {
        requireText(key, "key");
        cloud.apocalipse.pz.agent.features.npc.profiles.HumanProfile.identifier(profileId);
        requireText(username, "username");
        requireText(displayName, "displayName");
        requireText(outfit, "outfit");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(deathPolicy,"deathPolicy");
        Objects.requireNonNull(group,"group");
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0
            || x >= Integer.MAX_VALUE || y >= Integer.MAX_VALUE || z < -32 || z > 31) {
            throw new IllegalArgumentException("Invalid human world coordinates");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        }
    }

    /** Opt-in startup guide at the configured Apocalipse location, with JVM overrides. */
    static ServerHumanDefinition configuredGuide() {
        if (!Boolean.parseBoolean(AgentConfiguration.systemProperty(
            AgentConfiguration.NPC_ARG_APOCBR_GUIDE_ENABLED, "false"))) return null;
        String name = AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_NAME, "Apocalipse Guide");
        return new ServerHumanDefinition(
            "guide", name, name,
            Float.parseFloat(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_X, "14294")),
            Float.parseFloat(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_Y, "11862")),
            Integer.parseInt(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_Z, "0")),
            IsoDirections.valueOf(AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_FACING, "E").trim().toUpperCase(Locale.ROOT)),
            AgentConfiguration.booleanProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_FEMALE),
            AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_OUTFIT, "CostumeMonster"),
            AgentConfiguration.systemProperty(AgentConfiguration.NPC_ARG_APOCBR_GUIDE_PROFILE, "lobby-guide")
        );
    }
}
