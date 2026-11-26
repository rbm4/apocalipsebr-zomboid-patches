package cloud.apocalipse.pz.agent.features.clientmenu;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/** Client-only Lua/backend bridge for the future custom main menu. */
public final class ClientMenuModule implements AgentModule {
    private static final String STEAM = "zombie.core.znet.SteamUtils";
    private static final String LUA = "zombie.Lua.LuaManager";
    private boolean enabled;

    @Override public String name() { return "client-menu-api"; }

    @Override public void initialize() {
        enabled = AgentConfiguration.clientMenuEnabled();
    }

    @Override public String disabledReason() {
        return enabled ? null : "disabled by -Dapocbr.clientMenu.enabled=false";
    }

    @Override public List<HookCheck> hookChecks() {
        String runtime = ClientMenuRuntime.class.getName();
        return List.of(
            new HookCheck("steam-ready", STEAM, "init", runtime, "steamInitialized", false),
            new HookCheck("steam-callback-pump", STEAM, "runLoop", "cloud.apocalipse.pz.agent.features.clientmenu.SteamWebApiTicketProbe", "afterSteamLoop", false),
            new HookCheck("steam-shutdown", STEAM, "shutdown", runtime, "shutdown", false),
            new HookCheck("client-lua-api", LUA, "init", runtime, "luaInitialized", false)
        );
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder
            .type(named(STEAM)).transform((dynamic, type, loader, module, domain) ->
                dynamic
                    .visit(Advice.to(SteamInitialized.class).on(named("init").and(isStatic()).and(takesArguments(0))))
                    .visit(Advice.to(SteamLoop.class).on(named("runLoop").and(isStatic()).and(takesArguments(0))))
                    .visit(Advice.to(SteamShutdown.class).on(named("shutdown").and(isStatic()).and(takesArguments(0)))))
            .type(named(LUA)).transform((dynamic, type, loader, module, domain) ->
                dynamic.visit(Advice.to(LuaInitialized.class).on(named("init").and(isStatic()).and(takesArguments(0)))));
    }

    public static final class SteamInitialized {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable failure) {
            if (failure == null) {
                try { ClientMenuRuntime.steamInitialized(); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    public static final class LuaInitialized {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable failure) {
            if (failure == null) {
                try { ClientMenuRuntime.luaInitialized(); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }

    public static final class SteamShutdown {
        @Advice.OnMethodEnter
        public static void enter() {
            try { ClientMenuRuntime.shutdown(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    public static final class SteamLoop {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable failure) {
            if (failure == null) {
                try { SteamWebApiTicketProbe.afterSteamLoop(); }
                catch (RuntimeException | LinkageError ignored) { }
            }
        }
    }
}
