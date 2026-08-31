// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
// ApocBR patched: reject client-originated PlayerDamage that is not owned by its connection.
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

@PacketSetting(ordering = 0, priority = 2, reliability = 4, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class PlayerDamagePacket extends PlayerID implements INetworkPacket {
    private static final float APOCBR_DAMAGE_DROP_LOG_THRESHOLD = 25.0F;

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);

        try {
            b.putInt(this.getPlayer().getMaxWeight());
            b.putFloat(this.getPlayer().getCorpseSicknessRate());
            this.getPlayer().getBodyDamage().save(b.bb);
        } catch (IOException var3) {
            DebugType.Multiplayer.printException(var3, "PlayerDamagePacket: failed", LogSeverity.Error);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        if (!this.isConsistent(connection)) {
            return;
        }

        IsoPlayer player = this.getPlayer();
        if (!this.isOwnedByConnection(connection, player)) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PlayerDamageGuard] rejected unowned PlayerDamage"
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

        if (player.getOnlineID() != this.getID()) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PlayerDamageGuard] rejected PlayerDamage id mismatch"
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

        float healthBefore = player.getHealth();
        float bodyHealthBefore = player.getBodyDamage() != null ? player.getBodyDamage().getHealth() : -1.0F;

        try {
            player.setMaxWeight(b.getInt());
            player.setCorpseSicknessRate(b.getFloat());
            player.getBodyDamage().load(b.bb, IsoWorld.getWorldVersion());
        } catch (IOException var7) {
            DebugType.Multiplayer.printException(var7, "PlayerDamagePacket: failed", LogSeverity.Error);
            return;
        }

        float healthAfter = player.getHealth();
        float bodyHealthAfter = player.getBodyDamage() != null ? player.getBodyDamage().getHealth() : -1.0F;
        if (bodyHealthAfter <= 0.0F
            || healthAfter <= 0.0F
            || bodyHealthBefore - bodyHealthAfter >= APOCBR_DAMAGE_DROP_LOG_THRESHOLD
            || healthBefore - healthAfter >= APOCBR_DAMAGE_DROP_LOG_THRESHOLD) {
            DebugLog.log(
                DebugType.Multiplayer,
                "[ApocBR][PlayerDamageGuard] accepted dangerous PlayerDamage"
                    + " connectionUser="
                    + this.describeConnection(connection)
                    + " target="
                    + this.describePlayer(player)
                    + " health="
                    + healthBefore
                    + "->"
                    + healthAfter
                    + " bodyHealth="
                    + bodyHealthBefore
                    + "->"
                    + bodyHealthAfter
                    + " pos="
                    + player.getX()
                    + ","
                    + player.getY()
                    + ","
                    + player.getZ()
            );
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
