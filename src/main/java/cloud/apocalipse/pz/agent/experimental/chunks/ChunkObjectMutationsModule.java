package cloud.apocalipse.pz.agent.experimental.chunks;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.lua.HookPreflight;
import cloud.apocalipse.pz.agent.features.postgres.PostgresMigrations;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Direct mutation capture for the experimental ordinary-object projection. */
public final class ChunkObjectMutationsModule implements AgentModule {
    private static final String OBJECT = "zombie.iso.IsoObject";
    private static final String SQUARE = "zombie.iso.IsoGridSquare";
    private static final String MAP = "zombie.network.ServerMap";
    private static final String PLACEMENT = "(Lzombie/iso/IsoObject;I)V";
    private static final List<String> TARGETS = List.of(OBJECT, SQUARE, MAP);
    private boolean enabled;

    @Override public String name() { return "experimental-chunk-object-mutations"; }

    @Override public void initialize() {
        enabled = AgentConfiguration.chunkObjectMutationsEnabled();
        if (!enabled) return;
        if (!AgentConfiguration.postgresChunksEnabled()) {
            throw new IllegalStateException("Chunk object mutations require experimental PostgreSQL chunks");
        }
        HookPreflight.validate(TARGETS, hookChecks(), ChunkObjectMutationsModule::transform);
        PostgresMigrations.verifyChunkObjectSchema();
        ChunkObjectMutations.initialize(AgentConfiguration.postgresChunkDataset());
    }

    @Override public String disabledReason() {
        return enabled ? null : "Experimental chunk object mutation capture disabled by configuration";
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = ChunkObjectMutations.class.getName();
        return List.of(
            new HookCheck("tile-placement", SQUARE, "AddTileObject", helper, "changed", false),
            new HookCheck("special-placement", SQUARE, "AddSpecialObject", helper, "changed", false),
            new HookCheck("dirty-object", OBJECT, "flagForHotSave", helper, "changed", false),
            new HookCheck("object-sprite", OBJECT, "setSprite", helper, "changed", false),
            new HookCheck("object-color", OBJECT, "setCustomColor", helper, "changed", false),
            new HookCheck("object-removal", OBJECT, "removeFromSquare", helper, "removed", false),
            new HookCheck("object-reuse", OBJECT, "reset", helper, "reset", false),
            new HookCheck("frame-capture", MAP, "postupdate", helper, "captureFrame", false),
            new HookCheck("final-capture", MAP, "QueuedQuit", helper, "finishCapture", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        return enabled ? builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic)) : builder;
    }

    private static DynamicType.Builder<?> transform(String target, DynamicType.Builder<?> builder) {
        return switch (target) {
            case OBJECT -> builder
                .visit(Advice.to(Changed.class).on(named("flagForHotSave").and(hasDescriptor("()V"))
                    .or(named("setSprite").and(hasDescriptor("(Lzombie/iso/sprite/IsoSprite;)V")
                        .or(hasDescriptor("(Ljava/lang/String;)V"))))
                    .or(named("setCustomColor").and(hasDescriptor("(Lzombie/core/textures/ColorInfo;)V")))))
                .visit(Advice.to(Removal.class).on(named("removeFromSquare").and(hasDescriptor("()V"))))
                .visit(Advice.to(Reset.class).on(named("reset").and(hasDescriptor("()V"))));
            case SQUARE -> builder.visit(Advice.to(Placement.class).on(
                namedOneOf("AddTileObject", "AddSpecialObject").and(hasDescriptor(PLACEMENT))));
            case MAP -> builder
                .visit(Advice.to(Frame.class).on(named("postupdate").and(hasDescriptor("()V"))))
                .visit(Advice.to(Quit.class).on(named("QueuedQuit").and(hasDescriptor("()V"))));
            default -> throw new IllegalArgumentException(target);
        };
    }

    public static class Changed {
        @Advice.OnMethodExit public static void exit(@Advice.This Object object) {
            ChunkObjectMutations.changed(object);
        }
    }

    public static class Placement {
        @Advice.OnMethodExit public static void exit(@Advice.Argument(0) Object object) {
            ChunkObjectMutations.changed(object);
        }
    }

    public static class Removal {
        @Advice.OnMethodEnter public static Object enter(@Advice.This Object object) {
            return ChunkObjectMutations.beforeRemoval(object);
        }

        @Advice.OnMethodExit public static void exit(@Advice.This Object object, @Advice.Enter Object location) {
            ChunkObjectMutations.removed(object, location);
        }
    }

    public static class Reset {
        @Advice.OnMethodExit public static void exit(@Advice.This Object object) {
            ChunkObjectMutations.reset(object);
        }
    }

    public static class Frame {
        @Advice.OnMethodEnter public static void enter() {
            ChunkObjectMutations.captureFrame();
        }
    }

    public static class Quit {
        @Advice.OnMethodEnter public static void enter() {
            ChunkObjectMutations.finishCapture();
        }
    }
}
