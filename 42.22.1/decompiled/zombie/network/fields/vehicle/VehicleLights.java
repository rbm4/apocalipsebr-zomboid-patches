// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.vehicle;

import java.io.IOException;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.fields.INetworkPacketField;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

public class VehicleLights extends VehicleField implements INetworkPacketField {
    public VehicleLights(VehicleID vehicleID) {
        super(vehicleID);
    }

    @Override
    public void parse(ByteBufferReader bb, IConnection connection) {
        try {
            BaseVehicle vehicle = this.getVehicle();
            vehicle.setHeadlightsOn(bb.getBoolean());
            vehicle.setStoplightsOn(bb.getBoolean());
            int lightCount = bb.getByte() & 255;
            if (lightCount != vehicle.getLightCount()) {
                bb.position(bb.position() + lightCount);
                VehicleManager.instance.sendVehicleRequest(vehicle.getId(), (short)1);
                throw new IOException(String.format("Light count mismatch. Read %d, expected %d", lightCount, vehicle.getLightCount()));
            }

            for (int i = 0; i < lightCount; i++) {
                vehicle.getLightByIndex(i).getLight().setActive(bb.getBoolean());
            }
        } catch (Exception var6) {
            DebugType.Multiplayer.printException(var6, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        try {
            BaseVehicle vehicle = this.getVehicle();
            b.putBoolean(vehicle.getHeadlightsOn());
            b.putBoolean(vehicle.getStoplightsOn());
            int lightCount = vehicle.getLightCount();
            b.putByte(lightCount);

            for (int j = 0; j < lightCount; j++) {
                b.putBoolean(vehicle.getLightByIndex(j).getLight().getActive());
            }
        } catch (Exception var5) {
            DebugType.Multiplayer.printException(var5, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }
}
