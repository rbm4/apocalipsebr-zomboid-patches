// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.CellLoader;
import zombie.iso.IsoGridSquare;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.Square;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.AddItem, handlingType = 3)
public class AddObjectToMapPacket implements INetworkPacket {
    @JSONField
    private final Square square = new Square();
    @JSONField
    private String spriteName;

    @Override
    public void setData(Object... values) {
        this.square.set((IsoGridSquare)values[0]);
        this.spriteName = (String)values[1];
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.square.write(b);
        b.putUTF(this.spriteName);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.square.parse(b, connection);
        this.spriteName = b.getUTF();
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoSprite sprite = IsoSpriteManager.instance.namedMap.get(this.spriteName);
        if (sprite != null) {
            IsoGridSquare sq = this.square.getSquare();
            CellLoader.DoTileObjectCreation(sprite, sprite.getTileType(), sq, sq.getCell(), sq.getX(), sq.getY(), sq.getZ(), this.spriteName);
            sq.RecalcAllWithNeighbours(true);
            INetworkPacket.sendToRelative(PacketTypes.PacketType.AddObjectToMap, sq.getX(), sq.getY(), sq, this.spriteName);
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("OnTileObjectAdded", this.square.getSquare(), this.spriteName);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.square.isConsistent(connection);
    }
}
