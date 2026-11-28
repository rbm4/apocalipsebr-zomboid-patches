package cloud.apocalipse.pz.agent.features.steampeer;

import java.util.WeakHashMap;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

public final class SteamPeerRendezvous {
    static final String MODULE = "ApocBRSteamPeer";
    static final String HELLO = "helloV1";
    static final String PEER = "peerV1";
    static final long POLL_NS = 2_000_000_000L;
    static final long LEASE_NS = 6_000_000_000L;
    private static final WeakHashMap<IsoPlayer, Long> announced = new WeakHashMap<>();

    private static final WeakHashMap<IsoPlayer, Long> diagnostics = new WeakHashMap<>();

    public static void onHello(Object sender, Object args) {
        if (!GameServer.server || !(sender instanceof IsoPlayer player) || player.isDead()
                || player.getSteamID() == 0) return;
        long now = System.nanoTime();
        if (args instanceof KahluaTable request && request.rawget("diagnostic") instanceof String token) {
            if (!player.isAccessLevel("admin") || !token.matches("[0-9]{1,16}")) return;
            Long previous = diagnostics.get(player);
            if (previous != null && now - previous < POLL_NS) return;
            diagnostics.put(player, now);
            KahluaTable reply = LuaManager.platform.newTable();
            reply.rawset("diagnostic", token);
            GameServer.sendServerCommand(player, MODULE, PEER, reply);
            return;
        }
        Long previous = announced.get(player);
        if (previous != null && now - previous < POLL_NS / 2) return;
        announced.put(player, now);
        var players = GameServer.getPlayers();
        IsoPlayer peer = closest(player, players, now);
        // Mutual selection keeps this initial probe to one Steam channel per client.
        if (peer != null && closest(peer, players, now) != player) peer = null;
        KahluaTable reply = LuaManager.platform.newTable();
        reply.rawset("steamId", peer == null ? "0" : Long.toUnsignedString(peer.getSteamID()));
        GameServer.sendServerCommand(player, MODULE, PEER, reply);
    }

    private static IsoPlayer closest(IsoPlayer player, Iterable<IsoPlayer> players, long now) {
        IsoPlayer best = null;
        double bestDistance = 30 * 30;
        for (IsoPlayer other : players) {
            Long seen = announced.get(other);
            if (other.getSteamID() == 0 || other.getSteamID() == player.getSteamID()
                    || other.isDead() || seen == null || now - seen >= LEASE_NS) continue;
            double distance = distanceSquared(player.getX(), player.getY(), player.getZ(),
                    other.getX(), other.getY(), other.getZ());
            if (distance < bestDistance || distance == bestDistance &&
                    (best == null || other.getOnlineID() < best.getOnlineID())) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    static double distanceSquared(double x, double y, double z, double otherX, double otherY, double otherZ) {
        if (!(Math.abs(z - otherZ) < 1)) return Double.POSITIVE_INFINITY;
        double dx = x - otherX;
        double dy = y - otherY;
        return dx * dx + dy * dy;
    }

    private SteamPeerRendezvous() {}
}

