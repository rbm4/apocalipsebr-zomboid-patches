package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Type;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import static net.bytebuddy.jar.asm.Opcodes.ASM9;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
import static net.bytebuddy.jar.asm.Opcodes.INVOKEVIRTUAL;
import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Silences selected diagnostics for asset lookups with an existing vanilla fallback. */
public final class AssetLogModule implements AgentModule {
    private static final String DEBUG_TYPE = "zombie/debug/DebugType";
    private static final String OBJECT_LOG = "(Ljava/lang/Object;)V";
    private static final String FORMATTED_LOG = "(Ljava/lang/String;[Ljava/lang/Object;)V";
    private static final String EXCEPTION_LOG = "(Ljava/lang/Throwable;Ljava/lang/String;Lzombie/debug/LogSeverity;)V";
    private static final String DISCARD = "discard";

    private static final List<LogSite> SITES = List.of(
        new LogSite("invalid-sprite-config", "zombie.entity.components.spriteconfig.SpriteConfig",
            "initObjectInfo", "()V", "warn", OBJECT_LOG),
        new LogSite("unmapped-skeleton-bone", "zombie.core.skinnedmodel.model.SkinningBoneHierarchy",
            "buildBoneHierarchy", "(Lzombie/core/skinnedmodel/model/SkinningData;)V", "warn", FORMATTED_LOG),
        new LogSite("missing-mesh", "zombie.scripting.objects.ModelScript",
            "checkMesh", "(Ljava/lang/String;Ljava/lang/String;)V", "warn", OBJECT_LOG),
        new LogSite("unmapped-animation-channel", "zombie.core.skinnedmodel.model.jassimp.ImportedSkeleton",
            "collectBoneFrames", "(Ljassimp/AiAnimation;[Lgnu/trove/list/array/TFloatArrayList;Ljava/util/ArrayList;)V",
            "error", FORMATTED_LOG),
        new LogSite("custom-tile-property", "zombie.core.properties.IsoPropertyType",
            "lookupOrDefaultStr", "(Ljava/lang/String;)Ljava/lang/String;", "printException", EXCEPTION_LOG),
        new LogSite("duplicate-sprite-texture", "zombie.iso.sprite.IsoSpriteManager",
            "AddSprite", "(Ljava/lang/String;I)Lzombie/iso/sprite/IsoSprite;", "warn", OBJECT_LOG)
    );

    @Override
    public String name() {
        return "hotfixes.asset-logs";
    }

    @Override
    public String disabledReason() {
        String property = AgentConfiguration.ASSET_LOGS_HOTFIX_PROPERTY;
        return AgentConfiguration.optimizationEnabled(property) ? null : "Disabled by -D" + property;
    }

    @Override
    public List<HookCheck> hookChecks() {
        return SITES.stream().map(site ->
            new HookCheck(site.id(), site.target(), site.method(), AssetLogModule.class.getName(), DISCARD, false))
            .toList();
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        for (LogSite site : SITES) {
            builder = builder.type(named(site.target())).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                    .method(named(site.method()).and(hasDescriptor(site.methodDescriptor())),
                        (owner, method, visitor, context, pool, writer, reader) -> replaceLog(visitor, site))));
        }
        return builder;
    }

    private static MethodVisitor replaceLog(MethodVisitor visitor, LogSite site) {
        return new MethodVisitor(ASM9, visitor) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if (opcode == INVOKEVIRTUAL && owner.equals(DEBUG_TYPE)
                    && name.equals(site.logMethod()) && descriptor.equals(site.logDescriptor())) {
                    // The static sink consumes the same stack entries as the original call.
                    super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(AssetLogModule.class), DISCARD,
                        "(L" + DEBUG_TYPE + ";" + descriptor.substring(1), false);
                } else {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
            }
        };
    }

    public static void discard(DebugType logger, Object message) { }

    public static void discard(DebugType logger, String format, Object[] arguments) { }

    public static void discard(DebugType logger, Throwable failure, String message, LogSeverity severity) { }

    private record LogSite(String id, String target, String method, String methodDescriptor,
                           String logMethod, String logDescriptor) { }
}
