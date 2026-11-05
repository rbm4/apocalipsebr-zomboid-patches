// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.NetworkPlayerAI;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 9, priority = 2, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerInjuriesPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        IsoPlayer isoPlayer = this.player.getPlayer();
        b.putFloat(isoPlayer.getVariableFloat("IdleSpeed", 0.01F));
        b.putFloat(isoPlayer.getVariableFloat("StrafeSpeed", 1.0F));
        b.putFloat(isoPlayer.getVariableFloat("WalkInjury", 0.0F));
        NetworkPlayerAI networkAi = isoPlayer.getNetworkCharacterAI();
        b.putFloat(networkAi.walkSpeed);
        b.putFloat(networkAi.runSpeed);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (this.isConsistent(connection)) {
            IsoPlayer isoPlayer = this.player.getPlayer();
            isoPlayer.setVariable("IdleSpeed", b.getFloat());
            isoPlayer.setVariable("StrafeSpeed", b.getFloat());
            isoPlayer.setVariable("WalkInjury", b.getFloat());
            NetworkPlayerAI networkAi = isoPlayer.getNetworkCharacterAI();
            networkAi.walkSpeed = b.getFloat();
            networkAi.runSpeed = b.getFloat();
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
