package cloud.apocalipse.pz.agent.features.postgres;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import java.util.List;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.config.AgentConfiguration.PostgresSettings;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Account database hooks for original 42.22.2; other persistence owners have separate migration gaps. */
public final class PostgresModule implements AgentModule {
    public static final String ACCOUNT_TYPE = "zombie.network.ServerWorldDatabase";
    private PostgresSettings settings;

    @Override public String name() { return "postgresql"; }

    @Override public String disabledReason() {
        if (settings == null) throw new IllegalStateException("Module not initialized");
        return settings.enabled() && settings.accountsEnabled() ? null : "PostgreSQL account hooks disabled by configuration";
    }

    /** Primary installation checks; account-operation semantics remain separate test scenarios. */
    @Override public List<HookCheck> hookChecks() {
        String runtime = "cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime";
        return List.of(
            new HookCheck("accounts-bootstrap", ACCOUNT_TYPE, "create", runtime, "bootstrap", false),
            new HookCheck("server-lifecycle", "zombie.network.GameServer", "main", runtime, "verifyAccountHooks", false));
    }

    @Override public void initialize() {
        settings = AgentConfiguration.postgresSettings();
        if (settings.enabled() && settings.accountsEnabled()) preflight();
        PostgresRuntime.initialize(settings);
        System.out.println("[ApocBR-Agent][Postgres] Account hooks "
            + (settings.enabled() && settings.accountsEnabled() ? "enabled." : "disabled."));
        if (settings.enabled() && settings.accountsEnabled()) {
            System.out.println("[ApocBR-Agent][Postgres] Connection budgets: workflow="
                + settings.loginWorkflowMillis() + "ms, acquisition/network=" + settings.loginNetworkMillis()
                + "ms; poolSize=" + settings.poolSize() + ".");
        }
    }

    private static void preflight() {
        // TypePool inspects bytes without defining game classes before the agent is installed.
        try (var locator=ClassFileLocator.ForClassLoader.ofSystemLoader()) {
            var type=TypePool.Default.of(locator).describe(ACCOUNT_TYPE).resolve();
            transformAccounts(new ByteBuddy().redefine(type,locator)).make();
        } catch (Exception | LinkageError incompatible) {
            throw new IllegalStateException("PostgreSQL account hook preflight failed; server startup aborted");
        }
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (settings == null) throw new IllegalStateException("PostgreSQL module must be initialized first");
        if (!settings.enabled() || !settings.accountsEnabled()) return builder;
        return builder
            .with(new AgentBuilder.Listener.Compound(
                AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly(),
                new AgentBuilder.Listener.Adapter() {
                    @Override public void onTransformation(TypeDescription type, ClassLoader loader,
                            JavaModule module, boolean loaded, DynamicType dynamicType) {
                        if (ACCOUNT_TYPE.equals(type.getName())) PostgresRuntime.accountTransformed(loader);
                    }
                }))
            .type(named(ACCOUNT_TYPE))
            .transform((dynamic, type, loader, module, domain) -> transformAccounts(dynamic))
            .type(named("zombie.network.GameServer"))
            .transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(PostgresAdvice.ServerLifecycle.class)
                    .on(named("main").and(isStatic()).and(takesArguments(String[].class)).and(returns(void.class)))));
    }

    static DynamicType.Builder<?> transformAccounts(DynamicType.Builder<?> dynamic) {
        return dynamic
            .visit(new AccountConnectionVisitor())
            .visit(Advice.to(PostgresAdvice.AccountCall.class)
                .on(isMethod().and(namedOneOf(AccountConnectionVisitor.CALLS))
                    .and(not(namedOneOf("authClient", "authOwner", "googleAuthClient", "isNewAccountAllowed")))))
            .visit(Advice.to(PostgresAdvice.Authentication.class)
                .on(namedOneOf("authClient", "authOwner", "googleAuthClient")))
            .visit(Advice.to(PostgresAdvice.Bootstrap.class).on(named("create").and(takesArguments(0))))
            .visit(Advice.to(PostgresAdvice.Connect.class).on(named("connect").and(takesArguments(0))))
            .visit(Advice.to(PostgresAdvice.Close.class).on(named("close").and(takesArguments(0))))
            .visit(Advice.to(PostgresAdvice.NewAccount.class).on(named("isNewAccountAllowed")
                .and(takesArguments(String.class, String.class, long.class))));
    }
}
