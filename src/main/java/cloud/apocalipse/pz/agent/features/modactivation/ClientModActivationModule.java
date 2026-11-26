package cloud.apocalipse.pz.agent.features.modactivation;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.network.GameClient;
import zombie.network.GameServer;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Ensures ApocBR client Lua mods join the normal client mod-loading pass. */
public final class ClientModActivationModule implements AgentModule {
    private static final String FILE_SYSTEM = "zombie.ZomboidFileSystem";

    @Override public String name() { return "client-mod-activation"; }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("client-mod-list", FILE_SYSTEM, "loadMods",
            ClientModActivation.class.getName(), "includeConfiguredMods", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        return builder.type(named(FILE_SYSTEM)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(LoadClientMods.class)
                .on(named("loadMods").and(takesArguments(List.class)))));
    }

    public static final class LoadClientMods {
        @Advice.OnMethodEnter(suppress = RuntimeException.class)
        public static void enter(@Advice.Argument(value = 0, readOnly = false) List<String> modIds) {
            if (GameClient.client && !GameServer.server) {
                ClientModActivation.includeConfiguredMods(modIds);
            }
        }
    }
}
