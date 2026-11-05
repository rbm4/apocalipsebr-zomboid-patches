// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.iso.areas.SafeHouse;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.character.Player;
import zombie.util.StringUtils;

public class SafeHouseTitle implements INetworkPacketField {
    @JSONField
    protected final Player player;
    @JSONField
    private String title;

    protected SafeHouseTitle(Player player) {
        this.player = player;
    }

    public void set(IsoPlayer player, String title) {
        this.player.set(player);
        this.title = title;
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.title = b.getUTF();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putUTF(this.title);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!this.player.isConsistent(connection)) {
            DebugType.Multiplayer.error("player is not set");
            return false;
        } else if (StringUtils.isNullOrEmpty(this.title)) {
            DebugType.Multiplayer.error("title is not set");
            return false;
        } else if (!connection.getRole().hasCapability(Capability.CanSetupSafehouses) && SafeHouse.hasNotSurvivedEnoughToClaim(this.player.getPlayer())) {
            DebugType.Multiplayer.error("player is not survived enough");
            return false;
        } else {
            return true;
        }
    }

    public String getTitle() {
        return this.title;
    }

    public String getUsername() {
        return this.player.getPlayer().getUsername();
    }
}
