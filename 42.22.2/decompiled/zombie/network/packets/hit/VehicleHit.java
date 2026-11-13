// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.fields.hit.Fall;
import zombie.network.fields.hit.Player;
import zombie.network.fields.hit.VehicleDamage;
import zombie.network.fields.vehicle.VehicleID;
import zombie.vehicles.BaseVehicle;

public abstract class VehicleHit extends Player implements HitCharacter {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    public final VehicleDamage vehicleDamage = new VehicleDamage();
    @JSONField
    protected final Fall fall = new Fall();

    public void set(IsoPlayer wielder, IsoGameCharacter character, BaseVehicle vehicle, boolean isTargetHitFromBehind) {
        this.set(wielder);
        this.set(false);
        this.vehicleId.set(vehicle);
        this.vehicleDamage
            .set(
                character.getHitForce(),
                character.getHitDir().x,
                character.getHitDir().y,
                isTargetHitFromBehind,
                character.isStaggerBack(),
                character.isKnockedDown()
            );
        this.fall.set(character.getHitReactionNetworkAI());
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.vehicleId.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.vehicleId.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.vehicleId.isConsistent(connection) && connection.isOwnPlayerOnServer(this.getPlayer());
    }

    @Override
    public boolean isPostponed() {
        return true;
    }

    public BaseVehicle getVehicle() {
        return this.vehicleId.getVehicle();
    }
}
