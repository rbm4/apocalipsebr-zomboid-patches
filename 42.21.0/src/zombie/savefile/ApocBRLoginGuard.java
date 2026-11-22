package zombie.savefile;

import zombie.network.IConnection;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;

/** Fail closed at every connection phase, including reads performed before authClient. */
public final class ApocBRLoginGuard {
    public static boolean enabled(){return Boolean.getBoolean("apocbr.postgres.enabled")&&(ApocBRServerDatabase.enabled()||ApocBRPlayerDatabase.enabled());}
    public static boolean ready(IConnection connection) {
        if(!enabled()||ApocBRForegroundDatabase.withinDeadline()&&ApocBRPostgresConnector.acceptingLogins())return true;
        deny(connection);return false;
    }
    public static boolean continueRequest(IConnection connection) {
        if(!enabled()||ApocBRForegroundDatabase.withinDeadline()&&ApocBRPersistence.reachable())return true;
        deny(connection);return false;
    }
    public static boolean playerReady(IConnection connection,int slot) {
        if(!enabled())return true;
        if(!ApocBRForegroundDatabase.withinDeadline()){deny(connection);return false;}
        boolean coop=zombie.network.GameServer.coop&&zombie.core.znet.SteamUtils.isSteamModeEnabled();
        String identity=coop?connection.getIDStr():connection.getUserName();
        if(identity!=null&&ApocBRPostgresConnector.playerReadable(zombie.core.Core.gameSaveWorld,identity,slot,coop))return true;
        deny(connection);return false;
    }
    public static void deny(IConnection connection) {
        INetworkPacket.send(connection,PacketTypes.PacketType.AccessDenied,"AccessDenied");
        connection.forceDisconnect("access-denied-database-unavailable");
    }
    private ApocBRLoginGuard(){}
}
