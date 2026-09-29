// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.core.raknet.UdpConnection;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.PlayerID;
import zombie.network.packets.INetworkPacket;
import zombie.scripting.objects.ItemBodyLocation;

@PacketSetting(ordering = 0, priority = 2, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class PlayerSteppedOnGlassPacket extends PlayerID implements INetworkPacket {
    private static final int MAKE_MAX_RANDOM_INCLUSIVE = 1;

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0]);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer player = this.getPlayer();
        if (connection.hasPlayer(this.getID()) && !player.isInvulnerable() && !player.isSeatedInVehicle()) {
            InventoryItem shoes = player.getWornItems().getItem(ItemBodyLocation.SHOES);
            if (shoes == null || shoes.getCondition() <= 0) {
                int bodyPartTypeIndex = Rand.Next(BodyPartType.ToIndex(BodyPartType.Foot_L), BodyPartType.ToIndex(BodyPartType.Foot_R) + 1);
                BodyPart bodyPart = player.getBodyDamage().getBodyPart(BodyPartType.FromIndex(bodyPartTypeIndex));
                if (!bodyPart.isDeepWounded() || !bodyPart.haveGlass()) {
                    bodyPart.generateDeepShardWound();
                    INetworkPacket.send(PacketTypes.PacketType.PlayerDamage, player);
                }
            }
        }
    }
}
