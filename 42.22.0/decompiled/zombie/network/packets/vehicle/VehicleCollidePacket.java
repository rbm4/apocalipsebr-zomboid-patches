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
import zombie.network.fields.character.OnlinePlayer;
import zombie.network.fields.vehicle.VehicleID;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class VehicleCollidePacket implements INetworkPacket {
    @JSONField
    protected final VehicleID vehicleId = new VehicleID();
    @JSONField
    protected final OnlinePlayer player = new OnlinePlayer();
    @JSONField
    protected boolean isCollide;

    public void set(BaseVehicle vehicle, IsoPlayer player, boolean isCollide) {
        this.vehicleId.set(vehicle);
        this.player.set(player);
        this.isCollide = isCollide;
    }

    @Override
    public void setData(Object... values) {
        this.set((BaseVehicle)values[0], (IsoPlayer)values[1], (Boolean)values[2]);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.vehicleId.parse(b, connection);
        this.player.parse(b, connection);
        this.isCollide = b.getBoolean();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.vehicleId.write(b);
        this.player.write(b);
        b.putBoolean(this.isCollide);
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        this.vehicleId.getVehicle().authorizationServerCollide(this.player.getId(), this.isCollide);
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.vehicleId.isConsistent(connection);
    }
}
