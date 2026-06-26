// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.vehicle;

import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.fields.INetworkPacketField;

public class VehicleSounds extends VehicleField implements INetworkPacketField {
    public VehicleSounds(VehicleID vehicleID) {
        super(vehicleID);
    }

    @Override
    public void parse(ByteBufferReader bb, IConnection connection) {
        try {
            boolean soundAlarmOn = bb.getBoolean();
            boolean soundHornOn = bb.getBoolean();
            boolean soundBackMoveOn = bb.getBoolean();
            byte lightbarLightsMode = bb.getByte();
            byte lightbarSirenMode = bb.getByte();
            if (soundAlarmOn != this.getVehicle().soundAlarmOn) {
                if (soundAlarmOn) {
                    this.getVehicle().onAlarmStart();
                } else {
                    this.getVehicle().onAlarmStop();
                }
            }

            if (soundHornOn != this.getVehicle().soundHornOn) {
                if (soundHornOn) {
                    this.getVehicle().onHornStart();
                } else {
                    this.getVehicle().onHornStop();
                }
            }

            if (soundBackMoveOn != this.getVehicle().soundBackMoveOn) {
                if (soundBackMoveOn) {
                    this.getVehicle().onBackMoveSignalStart();
                } else {
                    this.getVehicle().onBackMoveSignalStop();
                }
            }

            if (this.getVehicle().lightbarLightsMode.get() != lightbarLightsMode) {
                this.getVehicle().setLightbarLightsMode(lightbarLightsMode);
            }

            if (this.getVehicle().lightbarSirenMode.get() != lightbarSirenMode) {
                this.getVehicle().setLightbarSirenMode(lightbarSirenMode);
            }
        } catch (Exception var8) {
            DebugType.Multiplayer.printException(var8, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        try {
            b.putBoolean(this.getVehicle().soundAlarmOn);
            b.putBoolean(this.getVehicle().soundHornOn);
            b.putBoolean(this.getVehicle().soundBackMoveOn);
            b.putByte(this.getVehicle().lightbarLightsMode.get());
            b.putByte(this.getVehicle().lightbarSirenMode.get());
        } catch (Exception var3) {
            DebugType.Multiplayer.printException(var3, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }
}
