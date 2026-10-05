// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: reject client-originated stat/nutrition sync for players not owned by the connection.
package zombie.network.packets;

import zombie.UsedFromLua;
import zombie.characters.Capability;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.PlayerID;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.CanModifyBodyStats, handlingType = 3)
@UsedFromLua
public class SyncPlayerStatsPacket implements INetworkPacket {
    @JSONField
    private final PlayerID playerId = new PlayerID();
    @JSONField
    private int syncParams;

    public static int getBitMaskForStat(CharacterStat stat) {
        for (int i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
            if (CharacterStat.ORDERED_STATS[i] == stat) {
                return 1 << i;
            }
        }

        return 0;
    }

    @Override
    public void setData(Object... values) {
        if (values[0] instanceof IsoPlayer) {
            this.playerId.set((IsoPlayer)values[0]);
            this.syncParams = (Integer)values[1];
        } else {
            DebugType.Multiplayer.warn(this.getClass().getSimpleName() + ".set get invalid arguments");
            DebugType.Multiplayer.printStackTrace();
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.playerId.parse(b, connection);
        this.syncParams = b.getInt();
        if (GameServer.server && !this.isOwnedByConnection(connection)) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][SyncPlayerStatsGuard] rejected unowned SyncPlayerStats"
                    + " connectionUser="
                    + this.describeConnection(connection)
                    + " packetId="
                    + this.playerId.getID()
                    + " packetIndex="
                    + this.playerId.getPlayerIndex()
                    + " syncParams="
                    + this.syncParams
                    + " nutritionPayload="
                    + (this.syncParams == -1)
                    + " resolved="
                    + this.describePlayer(this.playerId.getPlayer())
            );
            return;
        }

        if (this.syncParams == -1) {
            this.playerId.getPlayer().getNutrition().load(b.bb);
        } else {
            for (byte i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
                if ((this.syncParams >> i & 1) != 0) {
                    this.playerId.getPlayer().getStats().parse(b.bb, i);
                }
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.playerId.write(b);
        b.putInt(this.syncParams);
        if (this.syncParams == -1) {
            this.playerId.getPlayer().getNutrition().save(b.bb);
        } else {
            for (byte i = 0; i < CharacterStat.ORDERED_STATS.length; i++) {
                if ((this.syncParams >> i & 1) != 0) {
                    this.playerId.getPlayer().getStats().write(b.bb, i);
                }
            }
        }
    }

    private boolean isOwnedByConnection(IConnection connection) {
        IsoPlayer player = this.playerId.getPlayer();
        if (connection == null || player == null) {
            return false;
        }

        byte index = this.playerId.getPlayerIndex();
        if (index < 0 || index >= 4) {
            return false;
        }

        return GameServer.getPlayerFromConnection(connection, index) == player && connection.hasPlayer(player.getOnlineID());
    }

    private String describeConnection(IConnection connection) {
        if (connection == null) {
            return "null";
        }

        return connection.getUserName() + "/" + connection.getDescription() + "/guid=" + connection.getConnectedGUID();
    }

    private String describePlayer(IsoPlayer player) {
        if (player == null) {
            return "null";
        }

        return player.getUsername()
            + "/onlineId="
            + player.getOnlineID()
            + "/index="
            + player.getIndex()
            + "/pos="
            + player.getX()
            + ","
            + player.getY()
            + ","
            + player.getZ();
    }
}
