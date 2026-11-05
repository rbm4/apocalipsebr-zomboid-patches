package zombie.apocalipse.humans;

/** Fixed process-lifetime reservation shared by connection and NPC allocation. */
public final class ServerHumanPresentationIds {
    public static final int FIRST_ID = 768;
    public static final int LAST_ID = 1023;
    private ServerHumanPresentationIds() { }
    /** Reserve all four split-screen IDs, including IDs of NPCs not yet created. */
    public static boolean reservesConnectionSlot(int slot) {
        return slot >= FIRST_ID / 4 && slot <= LAST_ID / 4;
    }
}
