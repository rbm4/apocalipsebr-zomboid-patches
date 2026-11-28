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
    private static final String STEAM = "zombie.core.znet.SteamUtils";
    private static final String EVENTS = "zombie.Lua.LuaEventManager";
    private boolean enabled;

    @Override public String name() { return "steam-peer-probe"; }
    @Override public void initialize() { enabled = AgentConfiguration.steamPeerEnabled(); }
    @Override public String disabledReason() { return enabled ? null : "Steam peer probe disabled"; }

    @Override public List<HookCheck> hookChecks() {
        return List.of(
                new HookCheck("steam-server-peer-tick", STEAM, "runLoop", SteamServerPeerProbe.class.getName(), "tickServer", false),
                new HookCheck("steam-server-peer-shutdown", STEAM, "shutdown", SteamServerPeerProbe.class.getName(), "stopServer", false),
                new HookCheck("steam-server-peer-request", EVENTS, "triggerEvent", SteamServerPeerProbe.class.getName(), "start", false),
                new HookCheck("steam-server-peer-invite", EVENTS, "triggerEvent", SteamServerPeerProbe.class.getName(), "invite", false),
                new HookCheck("steam-peer-tick", CLIENT, "update", SteamPeerProbe.class.getName(), "tick", false),
                new HookCheck("steam-peer-disconnect", CLIENT, "disconnect", SteamPeerProbe.class.getName(), "reset", false),
                new HookCheck("steam-peer-lua-event", EVENTS, "register", SteamPeerProbe.class.getName(), "registerLuaEvent", false),
                new HookCheck("steam-peer-hello", EVENTS, "triggerEvent", SteamPeerRendezvous.class.getName(), "onHello", false),
                new HookCheck("steam-peer-grant", EVENTS, "triggerEvent", SteamPeerProbe.class.getName(), "grant", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (!enabled) return builder;
        return builder.type(named(CLIENT)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(ClientTick.class).on(named("update").and(takesArguments(0))))
                .visit(Advice.to(Disconnect.class).on(named("disconnect").and(takesArguments(boolean.class)))))
                .type(named(STEAM)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(ServerTick.class).on(named("runLoop").and(isStatic()).and(takesArguments(0))))
                .visit(Advice.to(SteamShutdown.class).on(named("shutdown").and(isStatic()).and(takesArguments(0)))))
                .type(named(EVENTS)).transform((dynamic, type, loader, module, domain) -> dynamic
                .visit(Advice.to(LuaEventsRegistered.class).on(named("register").and(isStatic()).and(takesArguments(2))))
                .visit(Advice.to(ServerRequest.class).on(named("triggerEvent").and(takesArguments(2))))
                .visit(Advice.to(Hello.class).on(named("triggerEvent").and(takesArguments(5))))
                .visit(Advice.to(Grant.class).on(named("triggerEvent").and(takesArguments(4)))));
    }

    public static class ClientTick {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit() { SteamServerPeerProbe.tickClient(); SteamPeerProbe.tick(); }
    }

    public static class Disconnect {
        @Advice.OnMethodEnter(suppress = Exception.class)
        public static void enter() { SteamServerPeerProbe.stopClient(); SteamPeerProbe.reset(); }
    }

    public static class LuaEventsRegistered {
        @Advice.OnMethodExit
        public static void exit() { SteamPeerProbe.registerLuaEvent(); }
    }

    public static class ServerTick {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit() { SteamServerPeerProbe.tickServer(); }
    }

    public static class SteamShutdown {
        @Advice.OnMethodEnter(suppress = Exception.class)
        public static void enter() {
            SteamServerPeerProbe.stopServer();
            SteamServerPeerProbe.stopClient();
        }
    }

    public static class ServerRequest {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit(@Advice.Argument(0) String event, @Advice.Argument(1) Object username) {
            if (SteamServerPeerProbe.REQUEST_EVENT.equals(event)) SteamServerPeerProbe.start(username);
        }
    }

    public static class Hello {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit(@Advice.Argument(0) String event, @Advice.Argument(1) Object module,
                @Advice.Argument(2) Object command, @Advice.Argument(3) Object sender, @Advice.Argument(4) Object args) {
            if ("OnClientCommand".equals(event) && "ApocBRSteamPeer".equals(module) && "helloV1".equals(command))
                SteamPeerRendezvous.onHello(sender, args);
        }
    }

    public static class Grant {
        @Advice.OnMethodExit(suppress = Exception.class)
        public static void exit(@Advice.Argument(0) String event, @Advice.Argument(1) Object module,
                @Advice.Argument(2) Object command, @Advice.Argument(3) Object args) {
            if (!"OnServerCommand".equals(event) || !"ApocBRSteamPeer".equals(module)) return;
            if ("peerV1".equals(command)) SteamPeerProbe.grant(args);
            else if (SteamServerPeerProbe.INVITE.equals(command)) SteamServerPeerProbe.invite(args);
        }
    }
}

