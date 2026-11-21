package zombie.Lua;

/** Structural double for a fresh-JVM packaged-agent smoke check; never packaged. */
public class LuaManager {
    public static se.krka.kahlua.vm.KahluaTable env;
    public static int informCalls;
    public static class GlobalObject {
        public static void inform(String message) { new ItemQueryJava().inform(message); }

        private static final class ItemQueryJava {
            private void inform(String message) {
                informCalls++;
                System.out.println("Original inform: " + message);
            }
        }
    }
}
