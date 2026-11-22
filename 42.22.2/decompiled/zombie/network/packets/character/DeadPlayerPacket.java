// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.FallingConstants;
import zombie.characters.IsoGameCharacter;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 0, priority = 0, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class DeadPlayerPacket extends DeadCharacterPacket implements INetworkPacket {
    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.writeCharacterInventory(b);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.parseCharacterInventory(b);
    }

    @Override
    public void postpone() {
        super.postpone();
        IsoGameCharacter character = this.getCharacter();
        if (character != null && character.isClimbing()) {
            if (character.isRemote()) {
                character.setHealth(0.0F);
            }

            character.setbClimbing(false);
            character.DoLand(FallingConstants.lethalFallThreshold);
        }
    }

    @Override
    public boolean isPostponed() {
        IsoGameCharacter character = this.getCharacter();
        return character != null && character.isClimbing();
    }
}
