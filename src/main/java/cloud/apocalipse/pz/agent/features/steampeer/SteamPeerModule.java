package cloud.apocalipse.pz.agent.features.steampeer;

import cloud.apocalipse.pz.agent.AgentModule;
import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class SteamPeerModule implements AgentModule {
    private static final String CLIENT = "zombie.network.GameClient";
    private static final String EVENTS = "zombie.Lua.LuaEventManager";
    private boolean enabled;

    @Override public String name() { return "steam-peer-probe"; }
    @Override public void initialize() { enabled = AgentConfiguration.steamPeerEnabled(); }
    @Override public String disabledReason() { return enabled ? null : "Steam peer probe disabled"; }

    @Override public List<HookCheck> hookChecks() {
        return List.of(
                new HookCheck("steam-peer-tick", CLIENT, "update", SteamPeerProbe.class.getName(), "tick", false),
                new HookCheck("steam-peer-disconnect", CLIENT, "disconnect", SteamPeerProbe.class.getName(), "reset", false),
                new HookCheck("steam-peer-hello", EVENTS, "triggerEvent", SteamPeerRendezvous.class.getName(), "onHello", false),
                new HookCheck("steam-peer-grant", EVENTS, "triggerEvent", SteamPeerProbe.class.getName(), "grant", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder.type(named(CLIENT)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(ClientTick.class).on(named("update").and(takesArguments(0))))
                .visit(Advice.to(Disconnect.class).on(named("disconnect").and(takesArguments(boolean.class)))))
                .type(named(EVENTS)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(Hello.class).on(named("triggerEvent").and(takesArguments(5))))
                .visit(Advice.to(Grant.class).on(named("triggerEvent").and(takesArguments(4)))));
    }

    public static class ClientTick {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit() { SteamPeerProbe.tick(); }
    }

    public static class Disconnect {
        @Advice.OnMethodEnter(suppress = Exception.class)
        public static void enter() { SteamPeerProbe.reset(); }
    }

    public static class Hello {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit(@Advice.Argument(0) String event, @Advice.Argument(1) Object module,
                @Advice.Argument(2) Object command, @Advice.Argument(3) Object sender) {
            if ("OnClientCommand".equals(event) && "ApocBRSteamPeer".equals(module) && "helloV1".equals(command))
                SteamPeerRendezvous.onHello(sender);
        }
    }

    public static class Grant {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit(@Advice.Argument(0) String event, @Advice.Argument(1) Object module,
                @Advice.Argument(2) Object command, @Advice.Argument(3) Object args) {
            if ("OnServerCommand".equals(event) && "ApocBRSteamPeer".equals(module) && "peerV1".equals(command))
                SteamPeerProbe.grant(args);
        }
    }
}

