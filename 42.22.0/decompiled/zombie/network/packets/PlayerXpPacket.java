// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.io.IOException;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.OnlinePlayer;

@PacketSetting(ordering = 11, priority = 2, reliability = 3, requiredCapability = Capability.CanModifyPlayerStatsInThePlayerStatsUI, handlingType = 3)
public class PlayerXpPacket implements INetworkPacket {
    @JSONField
    private final OnlinePlayer player = new OnlinePlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);

        try {
            this.player.getPlayer().getXp().save(b.bb);
        } catch (IOException var3) {
            DebugType.Multiplayer.printException(var3, "Player XP save error", LogSeverity.Error);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (this.isConsistent(connection)) {
            IsoPlayer isoPlayer = this.player.getPlayer();
            if (!isoPlayer.isDead()) {
                try {
                    isoPlayer.getXp().load(b.bb, 251);
                } catch (IOException var5) {
                    DebugType.Multiplayer.printException(var5, "Player XP load error", LogSeverity.Error);
                }
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.sendToClients(packetType, connection);
    }
}
