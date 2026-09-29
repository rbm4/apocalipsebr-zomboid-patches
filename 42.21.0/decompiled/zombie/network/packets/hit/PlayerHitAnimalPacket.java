// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitDamage;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.fields.character.AnimalID;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.hit.WeaponHit;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = {AntiCheat.HitDamage, AntiCheat.HitLongDistance, AntiCheat.HitWeapon}
)
public class PlayerHitAnimalPacket
    extends PlayerHitCharacter
    implements AntiCheatHitDamage.IAntiCheat,
    AntiCheatHitLongDistance.IAntiCheat,
    AntiCheatHitWeapon.IAntiCheat {
    @JSONField
    protected final AnimalID target = new AnimalID();

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (Boolean)values[2], (List<TracerInfo>)values[3], (List<WeaponHit>)values[4], (IsoAnimal)values[5]);
    }

    public void set(IsoPlayer wielder, HandWeapon weapon, boolean isCriticalHit, List<TracerInfo> tracers, List<WeaponHit> hits, IsoAnimal target) {
        this.set(wielder, weapon, isCriticalHit, tracers, hits);
        this.target.set(target);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection);
    }

    @Override
    public void process() {
        this.processHits(this.target.getAnimal());
    }

    @Override
    public void attack() {
        this.wielder.attack(this.getHandWeapon(), false, this.shotID, this.hitCount);
        this.processTracers();
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.wielder.getX(), this.wielder.getY());
    }

    @Override
    public IsoGameCharacter getTargetCharacter() {
        return this.target.getAnimal();
    }
}
