package cloud.apocalipse.pz.agent.features.lua.login;
import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
/** Admission depends on installed account hooks and each worker's bounded JDBC workflow. */
public final class ApocBRLoginGuard {
    private ApocBRLoginGuard() { }
    public static boolean ready(IConnection connection) {
        if(cloud.apocalipse.pz.agent.features.postgres.PostgresRuntime.acceptingAccounts())return true;
        deny(connection);return false;
    }
    public static void deny(IConnection connection) {
        INetworkPacket.send(connection,PacketTypes.PacketType.AccessDenied,"AccessDenied");
        connection.forceDisconnect("access-denied-database-unavailable");
    }
}
