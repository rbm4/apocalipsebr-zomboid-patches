// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.TradingRequests;
import zombie.core.TradingManager;
import zombie.core.TradingState;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class TradingUIUpdateStatePacket extends PlayerRequestPacket {
    @JSONField
    private TradingState state;

    @Override
    public void setData(Object... values) {
        super.setData(values);
        this.state = (TradingState)values[2];
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
        LuaEventManager.triggerEvent("TradingUIUpdateState", this.sender.getPlayer(), this.destination.getPlayer(), this.state);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (this.state == TradingState.WindowWasClosed) {
            TradingRequests.getInstance().response(this.sender.getPlayer(), this.destination.getPlayer());
        }

        if (!TradingManager.getInstance().isInvalid(this.sender.getPlayer(), this.destination.getPlayer())) {
            if (TradingManager.getInstance().processState(this.sender.getPlayer(), this.state)) {
                this.sendToClient(PacketTypes.PacketType.TradingUIUpdateState, this.destination.getPlayer());
            }
        }
    }
}
