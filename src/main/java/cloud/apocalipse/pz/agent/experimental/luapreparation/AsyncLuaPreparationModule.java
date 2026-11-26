package cloud.apocalipse.pz.agent.experimental.luapreparation;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.io.InputStreamReader;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Optional prefetch hooks for the original client multiplayer Lua loader. */
public final class AsyncLuaPreparationModule implements AgentModule {
    private static final String CONNECT = "zombie.gameStates.ConnectToServerState";
    private static final String FILE_SYSTEM = "zombie.ZomboidFileSystem";
    private static final String CORE = "zombie.core.Core";
    private static final String READER = "zombie.core.IndieFileLoader";
    private boolean enabled;

    @Override public String name() { return "experimental-async-lua-preparation"; }

    @Override public void initialize() { enabled = AgentConfiguration.asyncLuaPreparationEnabled(); }

    @Override public String disabledReason() {
        return enabled ? null : "Experimental client Lua preparation disabled by configuration";
    }

    @Override public List<HookCheck> hookChecks() {
        String helper = AsyncLuaPreparation.class.getName();
        return List.of(
            new HookCheck("join-start", CONNECT, "enter", helper, "startJoin", false),
            new HookCheck("join-exit", CONNECT, "exit", helper, "endJoin", false),
            new HookCheck("required-mods", FILE_SYSTEM, "loadModsAux", helper, "prepareRequiredMods", false),
            new HookCheck("loaded-mods", FILE_SYSTEM, "loadMods", helper, "prepareLoadedMods", false),
            new HookCheck("reload-start", CORE, "ResetLua", helper, "beginReload", false),
            new HookCheck("reload-exit", CORE, "ResetLua", helper, "endReload", false),
            new HookCheck("prepared-reader", READER, "getStreamReader", helper, "openPrepared", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder.type(named(CONNECT)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(JoinStart.class).on(named("enter").and(hasDescriptor("()V"))))
                .visit(Advice.to(JoinExit.class).on(named("exit").and(hasDescriptor("()V")))))
            .type(named(FILE_SYSTEM)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(RequiredMods.class).on(named("loadModsAux")
                    .and(hasDescriptor("(Ljava/util/List;Ljava/util/List;)Ljava/lang/String;"))))
                .visit(Advice.to(LoadedMods.class).on(named("loadMods").and(hasDescriptor("(Ljava/util/List;)V")))))
            .type(named(CORE)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Reload.class).on(named("ResetLua")
                    .and(hasDescriptor("(Ljava/lang/String;Ljava/lang/String;)V")))))
            .type(named(READER)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(PreparedReader.class).on(named("getStreamReader")
                    .and(hasDescriptor("(Ljava/lang/String;)Ljava/io/InputStreamReader;")))));
    }

    public static final class JoinStart {
        @Advice.OnMethodExit(suppress = RuntimeException.class)
        public static void exit(@Advice.This Object connectionState) {
            AsyncLuaPreparation.startJoin(connectionState);
        }
    }

    public static final class JoinExit {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = RuntimeException.class)
        public static void exit(@Advice.This Object connectionState) {
            AsyncLuaPreparation.endJoin(connectionState);
        }
    }

    public static final class RequiredMods {
        @Advice.OnMethodExit(suppress = RuntimeException.class)
        public static void exit(@Advice.Argument(0) List<String> requested, @Advice.Argument(1) List<String> resolved,
                @Advice.Return String missing) {
            AsyncLuaPreparation.prepareRequiredMods(requested, resolved, missing);
        }
    }

    public static final class LoadedMods {
        @Advice.OnMethodExit(suppress = RuntimeException.class)
        public static void exit() {
            AsyncLuaPreparation.prepareLoadedMods();
        }
    }

    public static final class Reload {
        @Advice.OnMethodEnter(suppress = RuntimeException.class)
        public static boolean enter(@Advice.Argument(1) String reason) {
            return AsyncLuaPreparation.beginReload(reason);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = RuntimeException.class)
        public static void exit(@Advice.Enter boolean opened) {
            AsyncLuaPreparation.endReload(opened);
        }
    }

    public static final class PreparedReader {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = RuntimeException.class)
        public static InputStreamReader enter(@Advice.Argument(0) String path) {
            return AsyncLuaPreparation.openPrepared(path);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter InputStreamReader prepared,
                @Advice.Return(readOnly = false) InputStreamReader result) {
            if (prepared != null) result = prepared;
        }
    }
}
