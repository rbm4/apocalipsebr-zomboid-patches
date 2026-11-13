package zombie.apocalipse.humans.encounters;

/** Pinned, typed policy; affiliation is independent of the actor/profile name. */
public record EncounterPolicy(String affiliation, int challengeTiles, int warningMs, int complianceMs,
                              int cooldownMs, int permissionMs, int refusalMs) {
    public static final EncounterPolicy DISABLED=new EncounterPolicy("",8,16000,1000,60000,300000,60000);
    public EncounterPolicy {
        if(affiliation==null||!affiliation.isEmpty()&&!affiliation.matches("[A-Za-z0-9_.-]{1,128}")
            ||challengeTiles<1||challengeTiles>32||warningMs<2000||warningMs>120000
            ||complianceMs<100||complianceMs>warningMs||cooldownMs<1000||cooldownMs>86400000
            ||permissionMs<1000||permissionMs>86400000||refusalMs<1000||refusalMs>86400000)
            throw new IllegalArgumentException("Invalid encounter policy");
    }
    public boolean enabled(){return !affiliation.isEmpty();}
}
