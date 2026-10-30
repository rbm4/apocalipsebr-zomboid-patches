// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.character.OnlinePlayer;

public abstract class PlayerRequestPacket implements INetworkPacket {
    @JSONField
    protected final ConnectionPlayer sender = new ConnectionPlayer();
    @JSONField
    protected final OnlinePlayer destination = new OnlinePlayer();

    @Override
    public void setData(Object... values) {
        this.sender.set((IsoPlayer)values[0]);
        this.destination.set((IsoPlayer)values[1]);
    }

    @Override
    public void parse(ByteBufferReader byteBufferReader, IConnection connection) {
        this.sender.parse(byteBufferReader, connection);
        this.destination.parse(byteBufferReader, connection);
    }

    @Override
    public void write(ByteBufferWriter byteBufferWriter) {
        this.sender.write(byteBufferWriter);
        this.destination.write(byteBufferWriter);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.sender.isConsistent(connection) && this.destination.isConsistent(connection);
    }
}
