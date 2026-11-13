// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitShortDistance;
import zombie.network.anticheats.AntiCheatHitVehicle;
import zombie.network.fields.character.AnimalID;
import zombie.vehicles.BaseVehicle;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 1,
    anticheats = {AntiCheat.HitShortDistance, AntiCheat.HitVehicle}
)
public class VehicleHitAnimalPacket extends VehicleHit implements AntiCheatHitShortDistance.IAntiCheat, AntiCheatHitVehicle.IAntiCheat {
    @JSONField
    protected final AnimalID target = new AnimalID();

    @Override
    public void setData(Object... values) {
        IsoAnimal animal = (IsoAnimal)values[1];
        this.set((IsoPlayer)values[0], animal, (BaseVehicle)values[2], (Boolean)values[3]);
        this.target.set(animal);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
        this.vehicleDamage.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.vehicleDamage.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection) && this.vehicleDamage.isConsistent(connection);
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getAnimal();
    }

    @Override
    public void process() {
        this.vehicleDamage.process(this.getPlayer(), this.target.getAnimal(), this.vehicleId.getVehicle());
    }

    @Override
    public float getFurthestHitDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }
}
