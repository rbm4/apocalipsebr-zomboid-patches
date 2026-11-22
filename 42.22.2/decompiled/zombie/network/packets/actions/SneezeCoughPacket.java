// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.actions;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.Translator;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 3, reliability = 0, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class SneezeCoughPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    protected byte value;

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (Integer)values[1], (Byte)values[2]);
    }

    public void set(IsoPlayer wielder, int sneezingCoughing, byte sneezeVar) {
        this.player.set(wielder);
        this.value = 0;
        if (sneezingCoughing % 2 == 0) {
            this.value = (byte)(this.value | 1);
        }

        if (sneezingCoughing > 2) {
            this.value = (byte)(this.value | 2);
        }

        if (sneezeVar > 1) {
            this.value = (byte)(this.value | 4);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        b.putByte(this.value);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.value = b.getByte();
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (isoPlayer != null) {
            boolean isSneeze = (this.value & 1) == 0;
            boolean isMuffled = (this.value & 2) != 0;
            int sneezeVar = (this.value & 4) == 0 ? 1 : 2;
            if (isoPlayer.isLocalPlayer()) {
                isoPlayer.setVariable("Ext", isSneeze ? "Sneeze" + sneezeVar : "Cough");
                isoPlayer.reportEvent("EventDoExt");
            }

            isoPlayer.Say(Translator.getText("IGUI_PlayerText_" + (isSneeze ? "Sneeze" : "Cough") + (isMuffled ? "Muffled" : "")));
            if (isSneeze) {
                if (isMuffled) {
                    isoPlayer.playerVoiceSound("SneezeLight");
                } else {
                    isoPlayer.playerVoiceSound("SneezeHeavy");
                }
            } else if (isMuffled) {
                isoPlayer.playerVoiceSound("MuffledCough");
            } else {
                isoPlayer.playerVoiceSound("Cough");
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }
}
