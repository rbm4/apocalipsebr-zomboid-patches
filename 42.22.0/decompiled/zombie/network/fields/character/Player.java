// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.character;

import java.nio.ByteBuffer;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.INetworkPacketField;
import zombie.network.fields.IPositional;

public abstract class Player implements INetworkPacketField, IPositional {
    protected static final short INVALID_ID = -1;
    private IsoPlayer player;
    @JSONField
    private short id = -1;

    public abstract void set(IsoPlayer var1);

    public short getId() {
        return this.id;
    }

    public void setId(short id) {
        this.id = id;
    }

    public IsoPlayer getPlayer() {
        return this.player;
    }

    public void setPlayer(IsoPlayer player) {
        this.player = player;
    }

    public void copy(Player other) {
        this.setId(other.id);
        this.setPlayer(other.player);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.write(b.bb);
    }

    public void write(ByteBuffer bb) {
        bb.putShort(this.getId());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.setId(b.getShort());
        this.setPlayer(null);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (this.getId() == -1) {
            DebugType.Multiplayer.error("invalid player index");
            return false;
        } else if (this.getPlayer() == null) {
            DebugType.Multiplayer.error("player is not found");
            return false;
        } else {
            return true;
        }
    }

    @Override
    public float getX() {
        return this.player != null ? this.player.getX() : Float.NaN;
    }

    @Override
    public float getY() {
        return this.player != null ? this.player.getY() : Float.NaN;
    }

    @Override
    public float getZ() {
        return this.player != null ? this.player.getZ() : Float.NaN;
    }

    protected static boolean isInvalidOnlineId(short onlineId) {
        return onlineId <= -1 || onlineId >= 1024;
    }

    protected static boolean isInvalidIndex(short index) {
        return index <= -1 || index >= 4;
    }
}
