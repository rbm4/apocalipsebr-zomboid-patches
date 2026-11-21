package cloud.apocalipse.pz.agent.features.postgres.dictionary;

import cloud.apocalipse.pz.agent.*;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.*;
import net.bytebuddy.pool.TypePool;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Dictionary setup only; native item/provenance decoder hooks are a subsequent port. */
public final class DictionaryModule implements AgentModule {
    public static final String TARGET = "zombie.world.WorldDictionary";
    private AgentConfiguration.DictionarySettings settings;
    @Override public String name() { return "postgresql-dictionary"; }
    @Override public String disabledReason() {
        if (settings == null) throw new IllegalStateException("Dictionary module not initialized");
        return settings.enabled() ? null : "PostgreSQL dictionary setup disabled by configuration";
    }
    @Override public List<HookCheck> hookChecks() {
        return List.of(
            new HookCheck("dictionary-publication", TARGET, "init", DictionaryRuntime.class.getName(), "afterRebuild", false),
            new HookCheck("dictionary-server-exit", "zombie.network.GameServer", "main",
                "cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime", "shutdownAfter", false),
            new HookCheck("dictionary-quit", "zombie.network.ServerMap", "QueuedQuit",
                "cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime", "shutdownAfter", false));
    }
    @Override public void initialize() {
        settings = AgentConfiguration.dictionarySettings();
        if (settings.enabled()) {
            try (var locator = ClassFileLocator.ForClassLoader.ofSystemLoader()) {
                var type = TypePool.Default.of(locator).describe(TARGET).resolve();
                transform(new ByteBuddy().redefine(type, locator)).make();
            } catch (Exception | LinkageError failure) { throw new IllegalStateException("Original dictionary setup preflight failed"); }
        }
        DictionaryRuntime.initialize(settings);
    }
    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (settings == null) throw new IllegalStateException("Dictionary module not initialized");
        if (!settings.enabled()) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) -> transform(dynamic))
            .type(named("zombie.network.GameServer")).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Exit.class).on(named("main").and(isStatic()).and(takesArguments(String[].class)).and(returns(void.class)))))
            .type(named("zombie.network.ServerMap")).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Exit.class).on(named("QueuedQuit").and(takesArguments(0)).and(returns(void.class)))));
    }
    static DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) { return builder.visit(new DictionarySetupVisitor()); }
    public static final class Exit {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown(readOnly = false) Throwable thrown) {
            thrown = cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.shutdownAfter(thrown);
        }
    }
}
