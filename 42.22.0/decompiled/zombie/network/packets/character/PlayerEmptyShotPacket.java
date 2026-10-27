// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.character;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.types.HandWeapon;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.packets.INetworkPacket;

@PacketSetting(ordering = 5, priority = 2, reliability = 0, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class PlayerEmptyShotPacket implements INetworkPacket {
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();

    @Override
    public void setData(Object... values) {
        this.player.set((IsoPlayer)values[0]);
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection);
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        HandWeapon weapon = isoPlayer.getAttackVars().getWeapon(isoPlayer);
        if (weapon != null && weapon.isAimedFirearm()) {
            isoPlayer.setInitiateAttack(true);
            isoPlayer.setAttackStarted(true);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        INetworkPacket.sendToRelative(PacketTypes.PacketType.PlayerEmptyShot, connection, isoPlayer.getX(), isoPlayer.getY(), isoPlayer);
    }
}
