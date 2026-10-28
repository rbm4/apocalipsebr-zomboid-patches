package zombie.apocalipse.humans;

import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.hit.Weapon;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.hit.PlayerHit;

/** Server-only writer for the native PlayerHit envelope, containing one authored child. */
final class ServerHumanCombatEnvelope implements INetworkPacket {
    private final ConnectionPlayer player=new ConnectionPlayer();
    private final Weapon weapon=new Weapon();
    private final PacketTypes.PacketType type;
    private final PlayerHit child;
    ServerHumanCombatEnvelope(IsoPlayer actor,HandWeapon handWeapon,PacketTypes.PacketType type,PlayerHit child){
        this.player.set(actor);this.weapon.set(handWeapon);this.type=type;this.child=child;
    }
    @Override public void parse(zombie.core.network.ByteBufferReader b,zombie.network.IConnection connection){throw new UnsupportedOperationException("server-only writer");}
    @Override public void write(ByteBufferWriter b){
        this.player.write(b);this.weapon.write(b);b.putByte(1);
        b.putInt(this.type.ordinal());this.child.write(b);
    }
}
