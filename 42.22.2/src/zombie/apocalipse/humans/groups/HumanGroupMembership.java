package zombie.apocalipse.humans.groups;

/** Durable configuration, scoped by the owning factory's server/world; never a native player ID. */
public record HumanGroupMembership(String groupId,int slot) {
    public static final HumanGroupMembership NONE=new HumanGroupMembership("",0);
    public HumanGroupMembership {
        if(groupId==null||(!groupId.isEmpty()&&!groupId.matches("[A-Za-z0-9_.-]{1,128}"))||slot<0||slot>7)
            throw new IllegalArgumentException("Invalid NPC group membership");
    }
    public boolean present(){return !groupId.isEmpty();}
}
