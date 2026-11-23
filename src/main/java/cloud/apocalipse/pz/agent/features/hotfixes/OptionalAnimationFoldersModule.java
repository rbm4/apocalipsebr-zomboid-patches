package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Skips absent animation and action-group roots during the vanilla checksum scan. */
public final class OptionalAnimationFoldersModule implements AgentModule {
    static final String TARGET = "zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator";
    private static final String METHOD = "searchFolders";

    @Override
    public String name() {
        return "hotfixes.optional-animation-folders";
    }

    @Override
    public String disabledReason() {
        String property = AgentConfiguration.OPTIONAL_ANIMATION_FOLDERS_HOTFIX_PROPERTY;
        return AgentConfiguration.optimizationEnabled(property) ? null : "Disabled by -D" + property;
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("optional-animation-folders", TARGET, METHOD,
            OptionalAnimationFoldersModule.class.getName(), "isMissing", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(SearchFolders.class).on(named(METHOD).and(isStatic())
                .and(takesArguments(URI.class, Path.class)).and(returns(List.class)))));
    }

    public static boolean isMissing(Path root) {
        return Files.notExists(root);
    }

    public static final class SearchFolders {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(1) Path root) {
            return isMissing(root);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter boolean missing, @Advice.Return(readOnly = false) List<String> files) {
            if (missing) {
                files = new ArrayList<>();
            }
        }
    }
}
