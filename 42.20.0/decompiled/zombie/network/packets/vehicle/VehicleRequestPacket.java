// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets.vehicle;

import gnu.trove.iterator.TShortShortIterator;
import gnu.trove.map.hash.TShortShortHashMap;
import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 1)
public class VehicleRequestPacket implements INetworkPacket {
    private TShortShortHashMap vehicleRequests;

    @Override
    public void setData(Object... values) {
        this.vehicleRequests = (TShortShortHashMap)values[0];
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int size = b.getShort();

        for (int i = 0; i < size; i++) {
            BaseVehicle vehicle = VehicleManager.instance.getVehicleByID(b.getShort());
            short flag = b.getShort();
            if (vehicle != null) {
                if (vehicle.connectionState[connection.getIndex()] == null) {
                    vehicle.connectionState[connection.getIndex()] = new BaseVehicle.ServerVehicleState();
                }

                if (flag == 16384) {
                    if (!connection.isRelevantTo(vehicle.getX(), vehicle.getY())) {
                        INetworkPacket.send(connection, PacketTypes.PacketType.VehicleRemove, vehicle);
                    }
                } else {
                    vehicle.connectionState[connection.getIndex()].flags |= flag;
                }
            }
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putShort(this.vehicleRequests.size());
        TShortShortIterator iterator = this.vehicleRequests.iterator();

        while (iterator.hasNext()) {
            iterator.advance();
            b.putShort(iterator.key());
            b.putShort(iterator.value());
        }
    }
}
