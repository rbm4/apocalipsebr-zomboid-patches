// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class SlowFactorPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    float slowTimer;
    @JSONField
    float slowFactor;

    @Override
    public void setData(Object... values) {
        IsoPlayer isoPlayer = (IsoPlayer)values[0];
        UdpConnection c = GameServer.getConnectionFromPlayer(isoPlayer);
        if (c != null) {
            this.player.set(isoPlayer);
            this.slowTimer = isoPlayer.getSlowTimer();
            this.slowFactor = isoPlayer.getSlowFactor();
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null && !isoPlayer.isDead()) {
            isoPlayer.setSlowTimer(this.slowTimer);
            isoPlayer.setSlowFactor(this.slowFactor);
            DebugLog.log(DebugType.Combat, "slowTimer=" + this.slowTimer + " slowFactor=" + this.slowFactor);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.slowTimer = b.getFloat();
        this.slowFactor = b.getFloat();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putFloat(this.slowTimer);
        b.putFloat(this.slowFactor);
    }
}
