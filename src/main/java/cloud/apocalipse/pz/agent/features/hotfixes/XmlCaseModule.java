package cloud.apocalipse.pz.agent.features.hotfixes;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Case-insensitive Linux folder/file lookup for vanilla PZ XML asset paths. */
public final class XmlCaseModule implements AgentModule {
    public static final String TARGET = "zombie.util.PZXmlUtil";
    public static final String METHOD = "parseXmlInternal";

    @Override
    public String name() {
        return "hotfixes.xml-case-insensitive";
    }

    @Override
    public String disabledReason() {
        return AgentConfiguration.optimizationEnabled(
            AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY)
            ? null
            : "Disabled by -D" + AgentConfiguration.XML_CASE_INSENSITIVE_HOTFIX_PROPERTY;
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("xml-case-insensitive", TARGET, METHOD,
            XmlCaseResolver.class.getName(), "resolve", true));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) {
            return builder;
        }
        return builder.type(named(TARGET)).transform((dynamicBuilder, type, loader, module, domain) ->
            dynamicBuilder.visit(Advice.to(XmlCaseAdvice.ParseInternal.class)
                .on(named(METHOD).and(takesArguments(String.class)))));
    }
}
