// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import java.io.IOException;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoWorld;
import zombie.network.IConnection;
import zombie.network.ApocBRPacketOwnershipGuard;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 10, priority = 2, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerDamagePacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);

        try {
            IsoPlayer isoPlayer = this.player.getPlayer();
            b.putInt(isoPlayer.getMaxWeight());
            b.putFloat(isoPlayer.getCorpseSicknessRate());
            isoPlayer.getBodyDamage().save(b.bb);
        } catch (IOException var3) {
            DebugType.Multiplayer.printException(var3, "PlayerDamagePacket: failed", LogSeverity.Error);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (ApocBRPacketOwnershipGuard.validate(
                "PlayerDamage", connection, this.player.getId(), (byte)-1, this.player.getPlayer()
            )
            && this.isConsistent(connection)) {
            try {
                IsoPlayer isoPlayer = this.player.getPlayer();
                isoPlayer.setMaxWeight(b.getInt());
                isoPlayer.setCorpseSicknessRate(b.getFloat());
                isoPlayer.getBodyDamage().load(b.bb, IsoWorld.getWorldVersion());
            } catch (IOException var4) {
                DebugType.Multiplayer.printException(var4, "PlayerDamagePacket: failed", LogSeverity.Error);
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
