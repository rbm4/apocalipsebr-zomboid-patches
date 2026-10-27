// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.character;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;

public class OnlinePlayer extends Player {
    @Override
    public void set(IsoPlayer player) {
        short onlineId;
        if (player == null) {
            onlineId = -1;
        } else {
            onlineId = player.getOnlineID();
        }

        this.setId(onlineId);
        this.setPlayer(player);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        short id = this.getId();
        if (!isInvalidOnlineId(id)) {
            if (GameServer.server) {
                this.setPlayer(GameServer.IDToPlayerMap.get(id));
            } else {
                this.setPlayer(GameClient.IDToPlayerMap.get(id));
            }
        }
    }
}
