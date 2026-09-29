// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.character.PlayerID;

public class Trading implements INetworkPacketField {
    private static final String PLAYER_A_IS_NOT_FOUND = "playerA is not found";
    private static final String PLAYER_B_IS_NOT_FOUND = "playerB is not found";
    protected static final String INVALID_PLAYER = "invalid player";
    @JSONField
    protected final PlayerID playerA = new PlayerID();
    @JSONField
    protected final PlayerID playerB = new PlayerID();

    public void set(IsoPlayer you, IsoPlayer other) {
        this.playerA.set(you);
        this.playerB.set(other);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.playerA.parse(b, connection);
        this.playerB.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.playerA.write(b);
        this.playerB.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (this.playerA.getPlayer() == null) {
            DebugType.Multiplayer.error("playerA is not found");
            return false;
        } else if (this.playerB.getPlayer() == null) {
            DebugType.Multiplayer.error("playerB is not found");
            return false;
        } else {
            return true;
        }
    }
}
