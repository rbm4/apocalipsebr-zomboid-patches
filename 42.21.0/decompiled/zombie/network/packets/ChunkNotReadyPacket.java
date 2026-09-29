// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.WorldStreamer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;

@PacketSetting(ordering = 4, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 0)
public class ChunkNotReadyPacket implements INetworkPacket {
    private final List<Integer> requestNumbers = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        this.requestNumbers.clear();

        for (int i = 0; i < values.length; i++) {
            this.requestNumbers.add((Integer)values[i]);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putInt(this.requestNumbers.size());

        for (int i = 0; i < this.requestNumbers.size(); i++) {
            b.putInt(this.requestNumbers.get(i));
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int count = b.getInt();

        for (int i = 0; i < count; i++) {
            WorldStreamer.instance.receiveChunkNotReady(b.getInt());
        }
    }
}
