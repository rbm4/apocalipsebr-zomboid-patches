package cloud.apocalipse.pz.agent.features.steampeer;

import java.util.concurrent.ThreadLocalRandom;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;
import zombie.core.znet.SteamUtils;
import zombie.network.GameClient;

public final class SteamPeerProbe {
    private static final boolean WINDOWS_X64 = System.getProperty("os.name", "").startsWith("Windows")
            && System.getProperty("os.arch", "").equals("amd64");
    private static final long RETRY_NS = 5_000_000_000L;
    private static UdpConnection connection;
    private static long lastHello;
    private static long lastPing;
    private static long grantedPeer;
    private static long grantedAt;
    private static long activePeer;
    private static SteamPeerTransport transport;
    private static SteamPeerExchange exchange;
    private static boolean nativeUnavailable;

    public static void tick() {
        if (!WINDOWS_X64 || !GameClient.client || !SteamUtils.isSteamModeEnabled()) return;
        UdpConnection current = GameClient.connection;
        if (current != connection) {
            reset();
            connection = current;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        if (current == null || player == null || player.isDead() || player.getOnlineID() == -1) {
            closePeer();
            grantedPeer = 0;
            return;
        }
        long now = System.nanoTime();
        try {
            if (nativeUnavailable) return;
            if (lastHello == 0 || now - lastHello >= SteamPeerRendezvous.POLL_NS) {
                // Resolve the live Steam interface before advertising capability.
                if (transport == null) {
                    try { transport = new SteamPeerTransport(); }
                    catch (VirtualMachineError fatal) { throw fatal; }
                    catch (Throwable failure) {
                        nativeUnavailable = true;
                        log("Steam interface unavailable: " + failure);
                        return;
                    }
                }
                lastHello = now;
                GameClient.instance.sendClientCommand(player, SteamPeerRendezvous.MODULE, SteamPeerRendezvous.HELLO, null);
            }
            long eligible = now - grantedAt < SteamPeerRendezvous.LEASE_NS ? grantedPeer : 0;
            if (eligible != activePeer) {
                closePeer();
                activePeer = eligible;
                if (eligible != 0) exchange = new SteamPeerExchange(eligible, ThreadLocalRandom.current().nextLong());
            }
            if (exchange == null) return;
            if (!exchange.confirmed() && (lastPing == 0 || now - lastPing >= RETRY_NS)) {
                lastPing = now;
                transport.send(activePeer, exchange.ping());
            }
            for (int i = 0; i < 8; i++) {
                var message = transport.receive(activePeer);
                if (message == null) break;
                boolean confirmed = exchange.confirmed();
                byte[] reply = exchange.receive(message.sender(), message.payload());
                if (reply != null) transport.send(activePeer, reply);
                if (!confirmed && exchange.confirmed()) log("Nearby peer round trip confirmed");
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            log("Probe failed: " + failure);
            reset();
            connection = current;
            nativeUnavailable = true;
        }
    }

    public static void grant(Object args) {
        if (!GameClient.client || connection == null || connection != GameClient.connection
                || !(args instanceof KahluaTable table) || !(table.rawget("steamId") instanceof String id)) return;
        try {
            grantedPeer = Long.parseUnsignedLong(id);
            grantedAt = System.nanoTime();
        } catch (NumberFormatException ignored) { }
    }

    public static void reset() {
        closePeer();
        connection = null;
        transport = null;
        grantedPeer = grantedAt = lastHello = 0;
        nativeUnavailable = false;
    }

    private static void closePeer() {
        if (transport != null && activePeer != 0) {
            try { transport.close(activePeer); }
            catch (VirtualMachineError fatal) { throw fatal; }
            catch (Throwable failure) { log("Channel close failed: " + failure); }
        }
        activePeer = lastPing = 0;
        exchange = null;
    }

    private static void log(String message) {
        System.out.println("[ApocBR-Agent][SteamPeer] " + message);
    }

    private SteamPeerProbe() {}
}

