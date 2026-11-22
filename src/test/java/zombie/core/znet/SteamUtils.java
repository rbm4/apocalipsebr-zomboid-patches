package zombie.core.znet;

public final class SteamUtils {
    public static boolean enabled=true;
    public static boolean isSteamModeEnabled() { return enabled; }
    public static String convertSteamIDToString(long id) { return Long.toUnsignedString(id); }
    public static long convertStringToSteamID(String id) { return Long.parseUnsignedLong(id); }
    public static void runLoop() { SteamWorkshop.instance.pump(); }
}
