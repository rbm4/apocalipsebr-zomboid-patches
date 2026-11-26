package cloud.apocalipse.pz.agent.features.modactivation;

import java.util.List;

/** Default client Lua mod IDs injected into the game's client load list. */
public final class ClientModActivation {
    private static final List<String> DEFAULT_MOD_IDS = List.of("APOCALISEBR_PACK");

    private ClientModActivation() { }

    public static void includeConfiguredMods(List<String> modIds) {
        if (modIds == null) return;
        for (String modId : DEFAULT_MOD_IDS) {
            if (!modIds.contains(modId)) modIds.add(modId);
        }
    }
}
