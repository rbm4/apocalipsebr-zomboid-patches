package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoChunkMap;
import zombie.network.GameServer;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Caps the local world window at the vanilla sizing boundary before world allocation. */
public final class ClientChunkMapModule implements AgentModule {
    private static final String TARGET = "zombie.iso.IsoChunkMap";
    private static final String METHOD = "CalcChunkWidth";
    private static final int MAX_WIDTH_IN_CHUNKS = 13;

    @Override public String name() { return "client-chunk-map"; }

    @Override public String disabledReason() {
        return AgentConfiguration.clientChunkMapOptimizationEnabled() ? null
            : "Enable with -D" + AgentConfiguration.CLIENT_CHUNK_MAP_OPTIMIZATION + "=true";
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("cap-client-chunk-map", TARGET, METHOD,
            getClass().getName(), "capWidth", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(ApplyCap.class).on(named(METHOD).and(isStatic()).and(hasDescriptor("()V")))));
    }

    public static void capWidth() {
        if (!GameServer.server && IsoChunkMap.chunkGridWidth > MAX_WIDTH_IN_CHUNKS) {
            IsoChunkMap.chunkGridWidth = MAX_WIDTH_IN_CHUNKS;
            IsoChunkMap.chunkWidthInTiles = MAX_WIDTH_IN_CHUNKS * IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        }
    }

    public static class ApplyCap {
        @Advice.OnMethodExit
        public static void exit() { capWidth(); }
    }
}
