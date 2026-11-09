package zombie.network;

import zombie.characters.IsoPlayer;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;

public final class ApocBRPacketOwnershipGuard {
    private ApocBRPacketOwnershipGuard() {
    }

    public static boolean validate(String packetType, IConnection connection, short claimedId, byte playerIndex, IsoPlayer resolvedPlayer) {
        // Client-bound stats/damage/effects packets are authoritative server updates.
        // Ownership of a sending client is meaningful only on the server receive side.
        if (!GameServer.server) return true;

        boolean owned = connection != null
            && resolvedPlayer != null
            && resolvedPlayer.getOnlineID() == claimedId
            && connection.hasPlayer(claimedId);
        if (owned) {
            if (playerIndex >= 0 && playerIndex < 4) {
                owned = GameServer.getPlayerFromConnection(connection, playerIndex) == resolvedPlayer;
            } else if (playerIndex == -1) {
                // PlayerID's vanilla sentinel selects by online ID. Still require the
                // exact resolved instance to belong to one of this sender's slots.
                owned = false;
                for (int slot = 0; slot < 4; slot++) {
                    if (GameServer.getPlayerFromConnection(connection, slot) == resolvedPlayer) {
                        owned = true;
                        break;
                    }
                }
            } else {
                owned = false;
            }
        }
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
