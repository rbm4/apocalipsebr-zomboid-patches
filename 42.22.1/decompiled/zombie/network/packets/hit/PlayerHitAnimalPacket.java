// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.CombatManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.anticheats.AntiCheatHitWeapon;
import zombie.network.fields.character.AnimalID;
import zombie.network.fields.hit.TracerInfo;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 0,
    anticheats = {AntiCheat.HitLongDistance, AntiCheat.HitWeapon}
)
public class PlayerHitAnimalPacket extends PlayerHit implements AntiCheatHitLongDistance.IAntiCheat, AntiCheatHitWeapon.IAntiCheat {
    @JSONField
    protected final AnimalID target = new AnimalID();

    public PlayerHitAnimalPacket() {
    }

    public PlayerHitAnimalPacket(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, hitList, tracers);
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (CombatManager.TargetHitList)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public void set(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, tracers, hitList.hits());
        this.target.set((IsoAnimal)hitList.target());
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
    public IsoObject getTarget() {
        return this.target.getAnimal();
    }

    @Override
    public void process() {
        this.processHits(this.target.getAnimal());
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }
}
