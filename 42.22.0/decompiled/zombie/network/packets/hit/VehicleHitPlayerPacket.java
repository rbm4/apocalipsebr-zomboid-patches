// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.hit;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
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
import zombie.network.anticheats.AntiCheatSafety;
import zombie.network.fields.hit.Player;
import zombie.vehicles.BaseVehicle;

@PacketSetting(
    ordering = 0,
    priority = 0,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = {AntiCheat.HitShortDistance, AntiCheat.Safety, AntiCheat.HitVehicle}
)
public class VehicleHitPlayerPacket
    extends VehicleHit
    implements AntiCheatHitShortDistance.IAntiCheat,
    AntiCheatSafety.IAntiCheat,
    AntiCheatHitVehicle.IAntiCheat {
    @JSONField
    protected final Player target = new Player();

    @Override
    public void setData(Object... values) {
        IsoPlayer player = (IsoPlayer)values[1];
        this.set((IsoPlayer)values[0], player, (BaseVehicle)values[2], (Boolean)values[3]);
        this.target.set(player);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        super.parse(b, connection);
        this.target.parse(b, connection);
        this.vehicleDamage.parse(b, connection);
        this.fall.parse(b, connection);
    }

    @Override
    public void write(ByteBufferWriter b) {
        super.write(b);
        this.target.write(b);
        this.vehicleDamage.write(b);
        this.fall.write(b);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return super.isConsistent(connection) && this.target.isConsistent(connection) && this.vehicleDamage.isConsistent(connection);
    }

    @Override
    public void preProcess() {
        super.preProcess();
        this.target.process();
    }

    @Override
    public void process() {
        this.vehicleDamage.process(this.getPlayer(), this.target.getCharacter(), this.vehicleId.getVehicle());
        this.fall.process(this.target.getCharacter());
    }

    @Override
    public void postProcess() {
        super.postProcess();
        this.target.process();
    }

    @Override
    public void react() {
        this.target.react();
    }

    @Override
    public void postpone() {
        this.target.getCharacter().getNetworkCharacterAI().setVehicleHit(this);
    }

    @Override
    public float getFurthestHitDistance() {
        return IsoUtils.DistanceTo(this.target.getX(), this.target.getY(), this.getX(), this.getY());
    }

    @Override
    public IsoObject getTarget() {
        return this.target.getPlayer();
    }
}
