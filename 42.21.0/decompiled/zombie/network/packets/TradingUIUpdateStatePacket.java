// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.TradingManager;
import zombie.core.TradingState;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.Trading;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIUpdateStatePacket extends Trading implements INetworkPacket {
    @JSONField
    private TradingState state;

    public void set(IsoPlayer you, IsoPlayer other, TradingState state) {
        super.set(you, other);
        this.state = state;
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (IsoPlayer)values[1], (TradingState)values[2]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        b.putEnum(this.state);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.state = b.getEnum(TradingState.class);
    }

    @Override
    public void processClient(UdpConnection connection) {
        LuaEventManager.triggerEvent("TradingUIUpdateState", this.playerA.getPlayer(), this.playerB.getPlayer(), this.state);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (!connection.hasPlayer(this.playerA.getID())) {
            DebugType.Multiplayer.error("invalid player");
        } else {
            if (TradingManager.getInstance().processState(this.playerA.getPlayer(), this.state)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIUpdateState, this.playerB.getPlayer().getUsername());
            }
        }
    }
}
