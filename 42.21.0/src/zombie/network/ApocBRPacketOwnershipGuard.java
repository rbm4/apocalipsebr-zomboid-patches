package zombie.network;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;

public final class ApocBRPacketOwnershipGuard {
    private ApocBRPacketOwnershipGuard() {
    }

    public static boolean validate(String packetType, IConnection connection, short claimedId, byte playerIndex, IsoPlayer resolvedPlayer) {
        boolean validIndex = playerIndex >= 0 && playerIndex < 4;
        boolean owned = validIndex
            && connection != null
            && resolvedPlayer != null
            && GameServer.getPlayerFromConnection(connection, playerIndex) == resolvedPlayer
            && resolvedPlayer.getOnlineID() == claimedId
            && connection.hasPlayer(claimedId);
        if (!owned) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PacketOwnership] rejected packet="
                    + packetType
                    + " user="
                    + (connection == null ? "null" : connection.getUserName())
                    + " claimedId="
                    + claimedId
                    + " playerIndex="
                    + playerIndex
                    + " resolvedId="
                    + (resolvedPlayer == null ? "null" : resolvedPlayer.getOnlineID())
            );
        }

        return owned;
    }
}
