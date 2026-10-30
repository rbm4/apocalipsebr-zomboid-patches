// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.io.IOException;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class HumanVisualPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (this.player.isConsistent(connection)) {
            try {
                this.player.getPlayer().getHumanVisual().load(b.bb, 251);
            } catch (IOException var4) {
                ExceptionLogger.logException(var4);
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);

        try {
            this.player.getPlayer().getHumanVisual().save(b.bb);
        } catch (IOException var3) {
            ExceptionLogger.logException(var3);
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.sendToClients(PacketTypes.PacketType.HumanVisual, connection);
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.player.getPlayer().resetModelNextFrame();
    }
}
