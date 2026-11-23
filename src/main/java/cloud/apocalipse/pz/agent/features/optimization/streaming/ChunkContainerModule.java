package cloud.apocalipse.pz.agent.features.optimization.streaming;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.network.GameServer;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Avoids repeated CONTAINER lookups for plain objects whose sprites cannot create a container. */
public final class ChunkContainerModule extends OptimizationModule {
    private static int integrationDepth;
    private static int skipped;

    public ChunkContainerModule() {
        super("chunk-container-checks", AgentConfiguration.CHUNK_CONTAINER_OPTIMIZATION);
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(
            new HookCheck("scope", "zombie.iso.IsoChunk", "doLoadGridsquare", getClass().getName(), "enterChunk", false),
            new HookCheck("container-check", "zombie.iso.IsoObject", "createContainersFromSpriteProperties",
                getClass().getName(), "skipContainerCheck", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named("zombie.iso.IsoChunk")).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(ChunkScope.class).on(named("doLoadGridsquare").and(hasDescriptor("()V")))))
            .type(named("zombie.iso.IsoObject")).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(ContainerCheck.class).on(named("createContainersFromSpriteProperties").and(hasDescriptor("()V")))));
    }

    public static boolean enterChunk() {
        if (!GameServer.server || Thread.currentThread() != GameServer.mainThread) return false;
        integrationDepth++;
        return true;
    }

    public static void leaveChunk() {
        if (--integrationDepth == 0 && skipped > 0) {
            OptimizationTelemetry.count(OptimizationTelemetry.Counter.CHUNK_CONTAINER_CHECK_SKIPPED, skipped);
            skipped = 0;
        }
    }

    public static boolean skipContainerCheck(Object object, Object sprite, Object container) {
        if (integrationDepth == 0 || Thread.currentThread() != GameServer.mainThread
                || object.getClass() != IsoObject.class || sprite == null || container != null) return false;
        var properties = ((IsoSprite) sprite).getProperties();
        if (properties.has(IsoFlagType.container) || properties.has("Freezer")) return false;
        skipped++;
        return true;
    }

    public static class ChunkScope {
        @Advice.OnMethodEnter public static boolean enter() { return enterChunk(); }
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter boolean active) { if (active) leaveChunk(); }
    }

    public static class ContainerCheck {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object object, @Advice.FieldValue("sprite") Object sprite,
                                    @Advice.FieldValue("container") Object container) {
            return skipContainerCheck(object, sprite, container);
        }
    }
}
