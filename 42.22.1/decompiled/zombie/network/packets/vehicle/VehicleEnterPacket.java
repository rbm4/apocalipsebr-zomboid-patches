// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.vehicle;

import org.joml.Vector3f;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoUtils;
import zombie.iso.Vector2;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.anticheats.AntiCheat;
import zombie.network.anticheats.AntiCheatVehicleEnter;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.scripting.objects.VehicleScript;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

@PacketSetting(
    ordering = 0,
    priority = 1,
    reliability = 3,
    requiredCapability = Capability.LoginOnServer,
    handlingType = 3,
    anticheats = AntiCheat.VehicleEnter
)
public class VehicleEnterPacket implements INetworkPacket, AntiCheatVehicleEnter.IAntiCheat {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    protected final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    protected int seatTo;
    private IsoPlayer passenger;

    public void set(BaseVehicle vehicle, IsoPlayer isoPlayer, int seatTo) {
        this.vehicleId.set(vehicle);
        this.player.set(isoPlayer);
        this.seatTo = seatTo;
    }

    @Override
    public void setData(Object... values) {
        this.set((BaseVehicle)values[0], (IsoPlayer)values[1], (Integer)values[2]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
        this.player.parse(b, connection);
        this.seatTo = b.getInt();
        if (this.vehicleId.getVehicle() != null) {
            this.passenger = Type.tryCastTo(this.vehicleId.getVehicle().getCharacter(this.seatTo), IsoPlayer.class);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
        this.player.write(b);
        b.putInt(this.seatTo);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.vehicleId.isConsistent(connection)
            && this.player.isConsistent(connection)
            && (this.passenger == null || this.passenger == this.player.getPlayer());
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.vehicleId.getVehicle().enterRSync(this.seatTo, this.player.getPlayer(), this.vehicleId.getVehicle());
    }

    @Override
    public void sync(PacketTypes.PacketType packetType, IConnection connection) {
        if (this.vehicleId.isConsistent(connection) && this.player.isConsistent(connection)) {
            INetworkPacket.send(connection, PacketTypes.PacketType.VehicleExit, this.vehicleId.getVehicle(), this.player.getPlayer(), this.seatTo);
        }
    }

    @Override
    public float getDistance() {
        BaseVehicle vehicle = this.vehicleId.getVehicle();
        IsoPlayer isoPlayer = this.player.getPlayer();
        VehicleScript.Position position = vehicle.getPassengerPosition(this.seatTo, "outside");
        VehicleScript.Position position2 = vehicle.getPassengerPosition(this.seatTo, "outside2");
        if (position == null && position2 == null) {
            VehiclePart door = vehicle.getPassengerDoor(this.seatTo);
            if (door != null) {
                Vector2 areaCenter = vehicle.getAreaCenter(door.getArea());
                if (areaCenter != null) {
                    return IsoUtils.DistanceTo(areaCenter.x, areaCenter.y, isoPlayer.getX(), isoPlayer.getY());
                }
            }

            return IsoUtils.DistanceTo(vehicle.getX(), vehicle.getY(), isoPlayer.getX(), isoPlayer.getY());
        } else {
            float distance = Float.POSITIVE_INFINITY;
            float distance2 = Float.POSITIVE_INFINITY;
            if (position != null) {
                Vector3f worldPos = vehicle.getPassengerPositionWorldPos(position, new Vector3f());
                distance = IsoUtils.DistanceTo(worldPos.x, worldPos.y, isoPlayer.getX(), isoPlayer.getY());
            }

            if (position2 != null) {
                Vector3f worldPos = vehicle.getPassengerPositionWorldPos(position2, new Vector3f());
                distance2 = IsoUtils.DistanceTo(worldPos.x, worldPos.y, isoPlayer.getX(), isoPlayer.getY());
            }

            return Float.min(distance, distance2);
        }
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.vehicleId.getVehicle().enter(this.seatTo, this.player.getPlayer());
        if (this.seatTo == 0
            && this.vehicleId.getVehicle().isEngineRunning()
            && this.vehicleId.getVehicle().isNetPlayerAuthorization(BaseVehicle.Authorization.Server)) {
            this.vehicleId.getVehicle().authorizationServerOnSeat(this.player.getPlayer(), true);
        }

        this.sendToClients(packetType, null);
    }
}
