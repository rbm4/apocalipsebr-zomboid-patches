// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.vehicle;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.ConnectionPlayer;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class VehicleSwitchSeatPacket implements INetworkPacket {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    protected final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    protected int seatFrom;
    @JSONField
    protected int seatTo;
    private IsoPlayer passenger;

    public void set(BaseVehicle vehicle, IsoPlayer isoPlayer, int seatFrom, int seatTo) {
        this.vehicleId.set(vehicle);
        this.player.set(isoPlayer);
        this.seatFrom = seatFrom;
        this.seatTo = seatTo;
    }

    @Override
    public void setData(Object... values) {
        this.set((BaseVehicle)values[0], (IsoPlayer)values[1], (Integer)values[2], (Integer)values[3]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
        this.player.parse(b, connection);
        this.seatFrom = b.getInt();
        this.seatTo = b.getInt();
        if (this.vehicleId.isConsistent(connection)) {
            this.passenger = Type.tryCastTo(this.vehicleId.getVehicle().getCharacter(this.seatTo), IsoPlayer.class);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
        this.player.write(b);
        b.putInt(this.seatFrom);
        b.putInt(this.seatTo);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.vehicleId.isConsistent(connection)
            && this.player.isConsistent(connection)
            && (this.passenger == null || this.passenger == this.player.getPlayer());
    }

    @Override
    public void sync(PacketTypes.PacketType packetType, IConnection connection) {
        if (this.vehicleId.isConsistent(connection) && this.player.isConsistent(connection)) {
            INetworkPacket.send(
                connection, PacketTypes.PacketType.VehicleSwitchSeat, this.vehicleId.getVehicle(), this.player.getPlayer(), this.seatTo, this.seatFrom
            );
        }
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.vehicleId.getVehicle().switchSeat(this.player.getPlayer(), this.seatTo);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.vehicleId.getVehicle().switchSeat(this.player.getPlayer(), this.seatTo);
        if (this.seatTo == 0
            && this.vehicleId.getVehicle().isEngineRunning()
            && this.vehicleId.getVehicle().isNetPlayerAuthorization(BaseVehicle.Authorization.Server)) {
            this.vehicleId.getVehicle().authorizationServerOnSeat(this.player.getPlayer(), true);
        } else if (this.seatFrom == 0) {
            this.vehicleId.getVehicle().authorizationServerOnSeat(this.player.getPlayer(), false);
        }

        this.sendToClients(packetType, null);
    }
}
