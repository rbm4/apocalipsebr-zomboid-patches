package cloud.apocalipse.pz.agent.features.steampeer;

import java.util.concurrent.ThreadLocalRandom;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.znet.SteamGameServer;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;
import zombie.network.GameServer;

/** Temporary server endpoint on a channel separate from nearby-player probes. */
public final class SteamServerPeerProbe {
    static final String REQUEST_EVENT = "OnApocBRSteamPeerServerProbe";
    static final String INVITE = "serverProbeV1";
    private static final int CHANNEL = 0x41505032;
    private static final long WINDOW_NS = 20_000_000_000L;
    private static final long RETRY_NS = 2_000_000_000L;
    private static Session serverSession;
    private static Session clientSession;
    private static IsoPlayer target;

    public static void start(Object username) {
        if (!GameServer.server || !(username instanceof String name) || name.isBlank()) return;
        if (!SteamUtils.isSteamModeEnabled() || !SteamPeerTransport.supportsNativeLayout()) {
            log("Server Steam peer probe requires a Steam-enabled x64 server");
            return;
        }
        for (IsoPlayer player : GameServer.getPlayers()) {
            if (!name.equalsIgnoreCase(player.getUsername()) || player.getSteamID() == 0) continue;
            try {
                long serverId = SteamGameServer.GetSteamID();
                if (serverId == 0 || serverId == player.getSteamID()) {
                    throw new IllegalStateException("Dedicated server has no distinct Steam identity");
                }
                stopServer();
                serverSession = new Session(true, player.getSteamID());
                target = player;
                KahluaTable invite = LuaManager.platform.newTable();
                invite.rawset("steamId", Long.toUnsignedString(serverId));
                GameServer.sendServerCommand(player, SteamPeerRendezvous.MODULE, INVITE, invite);
                log("Steam probe invitation sent to " + player.getUsername());
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                stopServer();
                log("Server probe could not start: " + failure);
            }
            return;
        }
        log("No online Steam player matches " + name);
    }

    public static void invite(Object args) {
        if (!GameClient.client || GameClient.connection == null || !SteamUtils.isSteamModeEnabled()
                || !SteamPeerTransport.supportsNativeLayout()
                || !(args instanceof KahluaTable table) || !(table.rawget("steamId") instanceof String id)) return;
        try {
            long peer = Long.parseUnsignedLong(id);
            if (peer == 0) return;
            stopClient();
            clientSession = new Session(false, peer);
            log("Accepted server Steam probe invitation");
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            log("Client probe could not start: " + failure);
        }
    }

    public static void tickServer() {
        if (!GameServer.server || serverSession == null) return;
        if (!GameServer.PlayerToAddressMap.containsKey(target) || !serverSession.tick()) stopServer();
    }

    public static void tickClient() {
        if (clientSession == null) return;
        if (!GameClient.client || GameClient.connection == null || !clientSession.tick()) stopClient();
    }

    public static void stopServer() {
        if (serverSession != null) serverSession.close();
        serverSession = null;
        target = null;
    }

    public static void stopClient() {
        if (clientSession != null) clientSession.close();
        clientSession = null;
    }

    private static void log(String message) {
        System.out.println("[ApocBR-Agent][SteamPeer] " + message);
    }

    private static final class Session {
        final boolean server;
        final long peer;
        final long started = System.nanoTime();
        final SteamPeerTransport transport;
        final SteamPeerExchange exchange;
        long lastSend;
        boolean sent;
        String lastSendError;

        Session(boolean server, long peer) throws Throwable {
            this.server = server;
            this.peer = peer;
            transport = new SteamPeerTransport(server, CHANNEL);
            exchange = new SteamPeerExchange(peer, ThreadLocalRandom.current().nextLong());
        }

        boolean tick() {
            long now = System.nanoTime();
            if (now - started >= WINDOW_NS) {
                if (!exchange.confirmed()) log("Server/client Steam probe timed out"
                        + (lastSendError == null ? "" : ": " + lastSendError));
                return false;
            }
            try {
                // Both endpoints send to the invited identity, implicitly accepting its Steam session.
                if (!exchange.confirmed() && (!sent || now - lastSend >= RETRY_NS)) {
                    sent = true;
                    lastSend = now;
                    send(exchange.ping());
                }
                for (int i = 0; i < 8; i++) {
                    SteamPeerTransport.Message message = transport.receive(peer);
                    if (message == null) break;
                    boolean confirmed = exchange.confirmed();
                    byte[] reply = exchange.receive(message.sender(), message.payload());
                    if (reply != null) send(reply);
                    if (!confirmed && exchange.confirmed()) {
                        log((server ? "Server" : "Client") + " confirmed Steam round trip with " + Long.toUnsignedString(peer));
                        if (!server) SteamPeerProbe.emitLua("serverProbeConfirmed", peer);
                    }
                }
                return true;
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                log("Server/client Steam receive failed: " + failure);
                return false;
            }
        }

        private void send(byte[] message) throws Throwable {
            try {
                transport.send(peer, message);
                lastSendError = null;
            } catch (IllegalStateException failure) {
                lastSendError = failure.getMessage();
            }
        }

        void close() {
            try { transport.close(peer); }
            catch (VirtualMachineError fatal) { throw fatal; }
            catch (Throwable failure) { log("Server probe channel close failed: " + failure); }
        }
    }

    private SteamServerPeerProbe() {}
}

