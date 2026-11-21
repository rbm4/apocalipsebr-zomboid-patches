package cloud.apocalipse.pz.agent.features.lua.events;

import zombie.core.Translator;

/** Event text adapter; independent of the unported NPC engine. */
final class HumanLanguage {
    static String text(String key, Object... arguments) {
        return switch (arguments.length) {
            case 0 -> Translator.getText(key);
            case 1 -> Translator.getText(key, arguments[0]);
            case 2 -> Translator.getText(key, arguments[0], arguments[1]);
            default -> throw new IllegalArgumentException("Unsupported event phrase");
        };
    }
}
