package cloud.apocalipse.pz.agent;

/** Declarative installation evidence, consumed by diagnostics and the opt-in integration suite.
 * This describes a hook contract; it does not execute or control a game feature.
 */
public record HookCheck(String id, String targetClass, String method, String helperClass,
                        String helperMethod, boolean loadBeforeStartup) {
    public HookCheck {
        for (String value : new String[]{id, targetClass, method, helperClass, helperMethod}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Empty hook check field");
        }
    }
}
