package cloud.apocalipse.pz.agent.features.postgres.player;

import cloud.apocalipse.pz.agent.*;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Original-JAR PostgreSQL player authority, including admission, creation, simulation and persistence. */
public final class PlayerModule implements AgentModule {
    public static final List<String> TARGETS = List.of("zombie.savefile.ServerPlayerDB", "zombie.savefile.ServerPlayerDB$NetworkCharacterData",
        "zombie.network.packets.connection.LoadPlayerProfilePacket", "zombie.network.packets.character.CreatePlayerPacket",
        "zombie.network.packets.connection.ConnectPacket", "zombie.network.GameServer", "zombie.network.NetworkPlayerManager",
        "zombie.iso.IsoCell", "zombie.Lua.LuaEventManager", "zombie.network.ServerMap");
    private boolean enabled;
    @Override public String name() { return "postgresql-players"; }
    @Override public String disabledReason() { return enabled ? null : "Player authority disabled; enable apocbr.postgres.players.enabled"; }
    @Override public void initialize() {
        enabled = AgentConfiguration.playerEnabled();
        if (enabled) {
            if (!AgentConfiguration.dictionaryDecodeEnabled() || !AgentConfiguration.asyncAccountsEnabled() || !AgentConfiguration.playerRelationalEnabled() || !AgentConfiguration.playerMutationsEnabled() || !AgentConfiguration.playerReconcileEnabled())
                throw new IllegalArgumentException("Player authority requires dictionary decode, async accounts, live mutations and graph reconciliation");
            cloud.apocalipse.pz.agent.features.lua.HookPreflight.validate(TARGETS, hookChecks(), PlayerModule::transform);
            // A lambda owned by this native-free module defers helper linkage until shutdown.
            cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.beforeShutdown(() -> PlayerRuntime.shutdown());
        }
        PlayerState.enabled=enabled;
    }
    @Override public List<HookCheck> hookChecks() {
        var checks = new ArrayList<HookCheck>(); String runtime = PlayerRuntime.class.getName();
        String[][] contracts = {
            {TARGETS.get(0), "create", runtime, "bootstrap"}, {TARGETS.get(0), "serverLoadNetworkCharacter", runtime, "loaded"},
            {TARGETS.get(0), "serverUpdateNetworkCharacterInt", runtime, "nativeSave"}, {TARGETS.get(0), "serverUpdateNetworkCharacter", runtime, "finalSave"},
            {TARGETS.get(0), "saveFinishWait", runtime, "drain"}, {TARGETS.get(0), "close", runtime, "closeDatabase"},
            {TARGETS.get(0), "process", runtime, "beginProcess"}, {TARGETS.get(0), "process", runtime, "endProcess"},
            {TARGETS.get(0), "serverConvertNetworkCharacter", runtime, "convertNative"}, {TARGETS.get(1), "<init>", runtime, "nativeCaptured"},
            {TARGETS.get(2), "processServer", runtime, "profile"}, {TARGETS.get(3), "processServer", runtime, "create"},
            {TARGETS.get(3), "processServer", runtime, "afterNativeCreate"}, {TARGETS.get(4), "parse", runtime, "connectPacket"},
            {TARGETS.get(5), "receivePlayerConnect", runtime, "connect"}, {TARGETS.get(5), "main", runtime, "update"},
            {TARGETS.get(6), "update", PlayerMutations.class.getName(), "beginTick"}, {TARGETS.get(6), "update", PlayerMutations.class.getName(), "observe"},
            {TARGETS.get(6), "update", PlayerMutations.class.getName(), "endTick"},
            {TARGETS.get(7), "addMovingObject", PlayerConstruction.class.getName(), "active"},
            {TARGETS.get(7), "addToProcessItems", PlayerConstruction.class.getName(), "active"},
            {TARGETS.get(8), "triggerEvent", PlayerConstruction.class.getName(), "active"},
            {TARGETS.get(5), "main", runtime, "serverMain"}, {TARGETS.get(9), "QueuedQuit", runtime, "beginShutdown"},
            {TARGETS.get(9), "QueuedQuit", runtime, "endShutdown"}
        };
        for (int i = 0; i < contracts.length; i++) { var c = contracts[i]; checks.add(new HookCheck("player-" + i, c[0], c[1], c[2], c[3], false)); }
        return List.copyOf(checks);
    }
    @Override public AgentBuilder configure(AgentBuilder builder) {
        return !enabled ? builder : builder.type(namedOneOf(TARGETS.toArray(String[]::new)))
            .transform((dynamic, type, loader, module, domain) -> transform(type.getName(), dynamic));
    }
    public static DynamicType.Builder<?> transform(String name, DynamicType.Builder<?> dynamic) {
        if (name.equals(TARGETS.get(0))) return dynamic
            .visit(Advice.to(PlayerAdvice.Bootstrap.class).on(named("create").and(takesArguments(0))))
            .visit(Advice.to(PlayerAdvice.Loaded.class).on(named("serverLoadNetworkCharacter").and(takesArguments(int.class, String.class))))
            .visit(Advice.to(PlayerAdvice.NativeSaved.class).on(named("serverUpdateNetworkCharacterInt").and(takesArguments(1))))
            .visit(Advice.to(PlayerAdvice.FinalSave.class).on(named("serverUpdateNetworkCharacter").and(takesArguments(3)).and(takesArgument(0, named("zombie.characters.IsoPlayer")))))
            .visit(Advice.to(PlayerAdvice.Drain.class).on(named("saveFinishWait").and(takesArguments(0))))
            .visit(Advice.to(PlayerAdvice.Close.class).on(named("close").and(takesArguments(0))))
            .visit(Advice.to(PlayerAdvice.Convert.class).on(named("serverConvertNetworkCharacter").and(takesArguments(String.class, String.class))))
            .visit(Advice.to(PlayerAdvice.Process.class).on(named("process").and(takesArguments(0))));
        if (name.equals(TARGETS.get(1))) return dynamic
            .visit(Advice.to(PlayerAdvice.Capture.class).on(isConstructor().and(takesArguments(3))))
            .visit(Advice.to(PlayerAdvice.ClientCapture.class).on(isConstructor().and(takesArguments(2))));
        if (name.equals(TARGETS.get(2))) return dynamic.visit(Advice.to(PlayerAdvice.Profile.class).on(named("processServer").and(takesArguments(2))));
        if (name.equals(TARGETS.get(3))) return dynamic.visit(new PlayerVisitor()).visit(Advice.to(PlayerAdvice.Create.class).on(named("processServer").and(takesArguments(2))));
        if (name.equals(TARGETS.get(4))) return dynamic.visit(Advice.to(PlayerAdvice.ConnectPacket.class).on(named("parse").and(takesArguments(2))));
        if (name.equals(TARGETS.get(5))) return dynamic.visit(new PlayerVisitor()).visit(Advice.to(PlayerAdvice.Main.class).on(named("main").and(takesArguments(String[].class)))).visit(Advice.to(PlayerAdvice.Connect.class)
            .on(named("receivePlayerConnect").and(takesArguments(3))));
        if (name.equals(TARGETS.get(6))) return dynamic.visit(new PlayerVisitor()).visit(Advice.to(PlayerAdvice.Tick.class).on(named("update").and(takesArguments(0))));
        if (name.equals(TARGETS.get(7))) return dynamic.visit(Advice.to(PlayerAdvice.Detached.class).on(namedOneOf("addMovingObject", "addToProcessItems")));
        if (name.equals(TARGETS.get(9))) return dynamic.visit(Advice.to(PlayerAdvice.Quit.class).on(named("QueuedQuit").and(takesArguments(0))));
        return dynamic.visit(Advice.to(PlayerAdvice.Detached.class).on(named("triggerEvent").and(isStatic()).and(returns(void.class))));
    }
}
