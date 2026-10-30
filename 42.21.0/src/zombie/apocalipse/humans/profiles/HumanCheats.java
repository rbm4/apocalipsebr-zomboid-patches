package zombie.apocalipse.humans.profiles;

/** Authored activation flags, never inferred from role or actor identity. */
public record HumanCheats(boolean godMode,boolean ghostMode,boolean invisible) {
    public static final HumanCheats DEFAULT=new HumanCheats(true,false,false);
}
