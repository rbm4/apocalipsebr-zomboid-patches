// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.ActionManager;
import zombie.core.GeneralAction;
import zombie.core.Transaction;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

@PacketSetting(ordering = 1, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class GeneralActionPacket extends GeneralAction implements INetworkPacket {
    @Override
    public void setData(Object... values) {
        this.id = (Byte)values[0];
        this.player.set((IsoPlayer)values[1]);
        this.state = Transaction.TransactionState.Reject;
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        if (Transaction.TransactionState.Reject == this.state) {
            DebugType.Action.trace("GeneralAction client reject %s", this.getDescription());
            ActionManager.stop(this);
        }
    }
}
