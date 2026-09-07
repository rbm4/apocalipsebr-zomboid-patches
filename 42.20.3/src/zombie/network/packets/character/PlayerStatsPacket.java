// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: reject client-originated player stat/nutrition updates for players not owned by the connection.
package zombie.network.packets.character;

import java.io.IOException;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 2, reliability = 4, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerStatsPacket extends PlayerID implements INetworkPacket {
    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);

        try {
            this.getPlayer().getStats().save(b.bb);
            this.getPlayer().getNutrition().save(b.bb);
            b.putFloat(this.getPlayer().getTimeSinceLastSmoke());
            this.getPlayer().getBodyDamage().saveMainFields(b.bb);
        } catch (IOException var3) {
            DebugType.Multiplayer.printException(var3, "PlayerStatsPacket: failed", LogSeverity.Error);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        if (!this.isConsistent(connection)) {
            return;
        }

        IsoPlayer player = this.getPlayer();
        if (GameServer.server && !this.isOwnedByConnection(connection, player)) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PlayerStatsGuard] rejected unowned PlayerStats"
                    + " connectionUser="
                    + this.describeConnection(connection)
                    + " packetId="
                    + this.getID()
                    + " packetIndex="
                    + this.getPlayerIndex()
                    + " resolved="
                    + this.describePlayer(player)
            );
            return;
        }

        try {
            player.getStats().load(b.bb, IsoWorld.getWorldVersion());
            player.getNutrition().load(b.bb);
            player.setTimeSinceLastSmoke(b.getFloat());
            player.getBodyDamage().loadMainFields(b.bb, IsoWorld.getWorldVersion());
        } catch (IOException var4) {
            DebugType.Multiplayer.printException(var4, "PlayerStatsPacket: failed", LogSeverity.Error);
        }
    }

    private boolean isOwnedByConnection(IConnection connection, IsoPlayer player) {
        if (connection == null || player == null) {
            return false;
        }

        byte index = this.getPlayerIndex();
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
