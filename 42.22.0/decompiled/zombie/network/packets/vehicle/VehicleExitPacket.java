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
import zombie.vehicles.BaseVehicle;

@PacketSetting(ordering = 0, priority = 1, reliability = 3, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class VehicleExitPacket implements INetworkPacket {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    private final ConnectionPlayer player = new ConnectionPlayer();
    @JSONField
    protected int seatFrom;

    public void set(BaseVehicle vehicle, IsoPlayer player, int seatFrom) {
        this.vehicleId.set(vehicle);
        this.player.set(player);
        this.seatFrom = seatFrom;
    }

    @Override
    public void setData(Object... values) {
        this.set((BaseVehicle)values[0], (IsoPlayer)values[1], (Integer)values[2]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
        this.player.parse(b, connection);
        this.seatFrom = b.getInt();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
        this.player.write(b);
        b.putInt(this.seatFrom);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.vehicleId.isConsistent(connection) && this.player.isConsistent(connection);
    }

    @Override
    public void processClient(UdpConnection connection) {
        this.vehicleId.getVehicle().exitRSync(this.player.getPlayer());
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        this.vehicleId.getVehicle().exit(isoPlayer);
        if (this.seatFrom == 0) {
            this.vehicleId.getVehicle().authorizationServerOnSeat(isoPlayer, false);
        }

        this.sendToClients(packetType, null);
        INetworkPacket.sendToRelative(PacketTypes.PacketType.Equip, connection, isoPlayer.getX(), isoPlayer.getY(), isoPlayer);
    }
}
