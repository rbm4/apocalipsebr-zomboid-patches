package cloud.apocalipse.pz.agent.features.optimization.player;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import zombie.characters.CharacterStat;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Avoids boxing the default for every read of a character stat. */
public final class CharacterStatsModule extends OptimizationModule {
    private static final String TARGET = "zombie.characters.Stats";

    public CharacterStatsModule() {
        super("character-stats-lookup", AgentConfiguration.CHARACTER_STATS_OPTIMIZATION);
    }

    @Override
    public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("stat-get", TARGET, "get", CharacterStatsModule.class.getName(), "read", false));
    }

    @Override
    public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) -> dynamic
            .method(named("get").and(hasDescriptor("(Lzombie/characters/CharacterStat;)F")))
            .intercept(MethodDelegation.withDefaultConfiguration().filter(named("read")).to(CharacterStatsModule.class)));
    }

    public static float read(@FieldValue("stats") Map<CharacterStat, Float> stats, @Argument(0) CharacterStat stat) {
        Objects.requireNonNull(stat);
        Float value = stats.get(stat);
        return value != null ? value.floatValue() : stat.getDefaultValue();
    }
}

