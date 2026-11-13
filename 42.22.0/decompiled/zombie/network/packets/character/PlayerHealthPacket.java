// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 14, priority = 2, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class PlayerHealthPacket implements INetworkPacket {
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

        for (BodyPartType bodyPartType : BodyPartType.values()) {
            if (bodyPartType != BodyPartType.MAX) {
                b.putFloat(isoPlayer.getBodyDamage().getBodyPart(bodyPartType).getHealth());
            }
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        if (this.isConsistent(connection)) {
            IsoPlayer isoPlayer = this.player.getPlayer();

            for (BodyPartType bodyPartType : BodyPartType.values()) {
                if (bodyPartType != BodyPartType.MAX) {
                    isoPlayer.getBodyDamage().getBodyPart(bodyPartType).SetHealth(b.getFloat());
                }
            }

            isoPlayer.getBodyDamage().calculateOverallHealth();
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
