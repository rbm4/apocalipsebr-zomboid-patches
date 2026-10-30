// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.IsoObject;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.fields.character.AnimalID;
import zombie.network.fields.hit.AnimalDamage;
import zombie.network.fields.hit.Player;

@PacketSetting(ordering = 0, priority = 0, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 2)
public class AnimalHitPlayerPacket extends AnimalID implements HitCharacter {
    @JSONField
    protected final Player target = new Player();
    @JSONField
    protected final AnimalDamage animalDamage = new AnimalDamage();

    @Override
    public void setData(Object... values) {
        this.set((IsoAnimal)values[0], (IsoPlayer)values[1], (Float)values[2], (Boolean)values[3]);
    }

    public void set(IsoAnimal wielder, IsoPlayer target, float damage, boolean ignore) {
        this.set(wielder);
        this.target.set(target);
        this.animalDamage.set(ignore, damage);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
        this.animalDamage.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.animalDamage.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection);
    }

    @Override
    public void preProcess() {
        this.target.process();
    }

    @Override
    public void process() {
        this.animalDamage.processPlayer(this.getAnimal(), this.target.getPlayer());
    }

    @Override
    public void postProcess() {
        this.target.process();
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getPlayer();
    }

    @Override
    public void react() {
        this.target.react();
    }
}
