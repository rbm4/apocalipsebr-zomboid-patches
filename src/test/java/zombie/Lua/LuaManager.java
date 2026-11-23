package zombie.Lua;

/** Structural double for a fresh-JVM packaged-agent smoke check; never packaged. */
public class LuaManager {
    public static se.krka.kahlua.vm.KahluaTable env;
    public static int informCalls;
    public static class GlobalObject {
        public static int vanillaChecks;

        public static void checkModsNeedUpdate(zombie.core.raknet.UdpConnection connection) { vanillaChecks++; }
        public static java.util.ArrayList<String> getSteamWorkshopItemIDs() {
            return new java.util.ArrayList<>(java.util.List.of("1", "2"));
        }

        public static void inform(String message) { new ItemQueryJava().inform(message); }

        private static final class ItemQueryJava {
            private void inform(String message) {
                informCalls++;
                System.out.println("Original inform: " + message);
            }
        }
    }
}
