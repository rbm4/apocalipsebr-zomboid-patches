// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.character;

import java.io.IOException;
import java.nio.ByteBuffer;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;

public class ConnectionPlayer extends Player {
    @Override
    public void set(IsoPlayer isoPlayer) {
        this.setPlayer(isoPlayer);
        short id;
        if (isoPlayer == null) {
            id = -1;
        } else if (GameServer.server) {
            id = isoPlayer.getOnlineID();
        } else if (isoPlayer.isLocalPlayer()) {
            id = (short)isoPlayer.getIndex();
        } else {
            DebugType.Multiplayer.error("remote connection player is not allowed on the client");
            id = -1;
        }

        this.setId(id);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        short id = this.getId();
        if (GameClient.client) {
            if (isInvalidOnlineId(id)) {
                return;
            }

            this.setPlayer(GameClient.IDToPlayerMap.get(id));
        } else {
            if (isInvalidIndex(id)) {
                DebugType.Multiplayer.error("wrong player connection index (%d)", id);
                return;
            }

            IsoPlayer isoPlayer = connection == null ? null : connection.getPlayerAt(id);
            this.setPlayer(isoPlayer);
            short onlineId = isoPlayer == null ? -1 : isoPlayer.getOnlineID();
            this.setId(onlineId);
        }
    }

    public void readRequestingPlayer(ByteBufferReader input, IConnection connection) throws IOException {
        this.set(null);
        if (input.getBoolean()) {
            this.parse(input, connection);
            if (!this.isConsistent(connection)) {
                throw new IOException("Player not found.");
            }
        }
    }

    public void writeRequestingPlayer(ByteBuffer output, IsoPlayer isoPlayer) {
        output.put((byte)(isoPlayer != null ? 1 : 0));
        if (isoPlayer != null) {
            this.set(isoPlayer);
            this.write(output);
        }
    }
}
