// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.faction;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 3, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class FactionStatsPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    private boolean isShowTag;
    @JSONField
    private boolean isFactionPvp;

    @Override
    public void setData(Object... values) {
        IsoPlayer isoPlayer = (IsoPlayer)values[0];
        this.player.set(isoPlayer);
        this.isShowTag = isoPlayer.isShowTag();
        this.isFactionPvp = isoPlayer.isFactionPvp();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putBoolean(this.isShowTag);
        b.putBoolean(this.isFactionPvp);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.isShowTag = b.getBoolean();
        this.isFactionPvp = b.getBoolean();
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        if (!this.player.isConsistent(connection)) {
            DebugType.Multiplayer.error("player is not set");
            return false;
        } else {
            return true;
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        isoPlayer.setShowTag(this.isShowTag);
        isoPlayer.setFactionPvp(this.isFactionPvp);
        this.sendToClients(PacketTypes.PacketType.FactionStats, connection);
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        isoPlayer.setShowTag(this.isShowTag);
        isoPlayer.setFactionPvp(this.isFactionPvp);
        LuaEventManager.triggerEvent("SyncFaction", "");
    }
}
