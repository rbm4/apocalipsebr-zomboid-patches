// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.GameTime;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.ApocBRPacketOwnershipGuard;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 13, priority = 2, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerEffectsPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        IsoPlayer isoPlayer = this.player.getPlayer();
        b.putFloat(isoPlayer.getSleepingTabletEffect());
        b.putFloat(isoPlayer.getSleepingTabletDelta());
        b.putInt(isoPlayer.getSleepingPillsTaken());
        b.putFloat(isoPlayer.getBetaEffect());
        b.putFloat(isoPlayer.getBetaDelta());
        b.putFloat(isoPlayer.getDepressEffect());
        b.putFloat(isoPlayer.getDepressDelta());
        b.putFloat(isoPlayer.getPainEffect());
        b.putFloat(isoPlayer.getPainDelta());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (ApocBRPacketOwnershipGuard.validate(
                "PlayerEffects", connection, this.player.getId(), (byte)-1, this.player.getPlayer()
            )
            && this.isConsistent(connection)
            && GameTime.instance.calender != null) {
            IsoPlayer isoPlayer = this.player.getPlayer();
            isoPlayer.setSleepingTabletEffect(b.getFloat());
            isoPlayer.setSleepingTabletDelta(b.getFloat());
            isoPlayer.setSleepingPillsTaken(b.getInt());
            isoPlayer.setBetaEffect(b.getFloat());
            isoPlayer.setBetaDelta(b.getFloat());
            isoPlayer.setDepressEffect(b.getFloat());
            isoPlayer.setDepressDelta(b.getFloat());
            isoPlayer.setPainEffect(b.getFloat());
            isoPlayer.setPainDelta(b.getFloat());
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
