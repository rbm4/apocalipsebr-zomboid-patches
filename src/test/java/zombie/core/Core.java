package zombie.core;

/** Structural double for the target Core version access; never packaged. */
public final class Core {
    private static final Core INSTANCE = new Core();
    public static String gameSaveWorld = "npc-fixture";

    public static Core getInstance() {
        return INSTANCE;
    }

    public String getVersionNumber() {
        return "42.22";
    }
}
