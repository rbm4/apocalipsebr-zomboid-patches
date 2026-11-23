package cloud.apocalipse.pz.agent.integration;

/** Console-only colors; the raw child log remains plain text. */
final class IntegrationLog {
    private IntegrationLog() { }

    static void info(String message) { System.out.println("[Server-IT] " + message); }
    static void pass(String message) { result("PASS", "32", message); }
    static void fail(String message) { result("FAIL", "31", message); }
    static void skip(String message) { result("SKIP", "33", message); }

    private static void result(String status, String color, String message) {
        boolean enabled = colorEnabled(System.getProperty("pz.it.color"), System.getenv("NO_COLOR"));
        System.out.println(format(status, color, message, enabled));
    }

    static boolean colorEnabled(String override, String noColor) {
        return override == null ? noColor == null : Boolean.parseBoolean(override);
    }

    static String format(String status, String color, String message, boolean enabled) {
        String line = "[Server-IT] [" + status + "] " + message;
        return enabled ? "\u001b[" + color + "m" + line + "\u001b[0m" : line;
    }
}
