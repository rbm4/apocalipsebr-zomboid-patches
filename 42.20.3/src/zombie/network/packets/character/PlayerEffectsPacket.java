// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: reject client-originated player effect updates for players not owned by the connection.
package zombie.network.packets.character;

import zombie.GameTime;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 2, reliability = 4, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerEffectsPacket extends PlayerID implements INetworkPacket {
    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putFloat(this.getPlayer().getSleepingTabletEffect());
        b.putFloat(this.getPlayer().getSleepingTabletDelta());
        b.putInt(this.getPlayer().getSleepingPillsTaken());
        b.putFloat(this.getPlayer().getBetaEffect());
        b.putFloat(this.getPlayer().getBetaDelta());
        b.putFloat(this.getPlayer().getDepressEffect());
        b.putFloat(this.getPlayer().getDepressDelta());
        b.putFloat(this.getPlayer().getPainEffect());
        b.putFloat(this.getPlayer().getPainDelta());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        if (!this.isConsistent(connection) || GameTime.instance.calender == null) {
            return;
        }

        IsoPlayer player = this.getPlayer();
        if (GameServer.server && !this.isOwnedByConnection(connection, player)) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PlayerEffectsGuard] rejected unowned PlayerEffects"
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

        player.setSleepingTabletEffect(b.getFloat());
        player.setSleepingTabletDelta(b.getFloat());
        player.setSleepingPillsTaken(b.getInt());
        player.setBetaEffect(b.getFloat());
        player.setBetaDelta(b.getFloat());
        player.setDepressEffect(b.getFloat());
        player.setDepressDelta(b.getFloat());
        player.setPainEffect(b.getFloat());
        player.setPainDelta(b.getFloat());
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
