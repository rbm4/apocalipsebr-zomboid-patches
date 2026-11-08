// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import java.util.List;
import zombie.CombatManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatHitLongDistance;
import zombie.network.fields.hit.Hit;
import zombie.network.fields.hit.TracerInfo;
import zombie.network.fields.vehicle.VehicleID;
import zombie.vehicles.BaseVehicle;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 0,
    anticheats = AntiCheat.HitLongDistance
)
public class PlayerHitVehiclePacket extends PlayerHit implements AntiCheatHitLongDistance.IAntiCheat {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();

    public PlayerHitVehiclePacket() {
    }

    public PlayerHitVehiclePacket(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, hitList, tracers);
    }

    @Override
    public void setData(Object... values) {
        this.set((IsoPlayer)values[0], (HandWeapon)values[1], (CombatManager.TargetHitList)values[2], (List<TracerInfo>)values[3]);
    }

    @Override
    public void set(IsoPlayer wielder, HandWeapon weapon, CombatManager.TargetHitList hitList, List<TracerInfo> tracers) {
        this.set(wielder, weapon, tracers, hitList.hits());
        this.vehicleId.set((BaseVehicle)hitList.target());
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
        return super.isConsistent(connection) && this.vehicleId.isConsistent(connection);
    }

    @Override
    public IsoObject getTarget() {
        return this.vehicleId.getVehicle();
    }

    @Override
    public void process() {
        if (GameServer.server) {
            BaseVehicle vehicle = this.vehicleId.getVehicle();
            IsoPlayer wielderPlayer = this.getPlayer();
            boolean isNotThrowable = this.getHandWeapon().getPhysicsObject() == null;

            for (int i = 0; i < this.getHitCount(); i++) {
                CombatManager.HitData hitData = Hit.getHitData(wielderPlayer, vehicle, this.getHandWeapon());
                if (hitData != null) {
                    CombatManager.HitDamageData hitDamageData = CombatManager.getInstance()
                        .getHitDamage(wielderPlayer, this.getHandWeapon(), vehicle, hitData, getHitIndex());
                    vehicle.processHit(wielderPlayer, this.getHandWeapon(), hitDamageData.damageSplit());
                    if (isNotThrowable) {
                        CombatManager.getInstance().processMaintenanceCheck(wielderPlayer, this.getHandWeapon(), vehicle);
                    }
                }
            }
        }
    }

    @Override
    public float getDistance() {
        return IsoUtils.DistanceTo(this.vehicleId.getX(), this.vehicleId.getY(), this.getX(), this.getY());
    }
}
