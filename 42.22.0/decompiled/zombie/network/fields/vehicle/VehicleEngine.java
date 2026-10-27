// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.fields.vehicle;

import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.network.IConnection;
import zombie.network.fields.INetworkPacketField;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleEngineStateChangeReason;

public class VehicleEngine extends VehicleField implements INetworkPacketField {
    public VehicleEngine(VehicleID vehicleID) {
        super(vehicleID);
    }

    @Override
    public void parse(ByteBufferReader bb, IConnection connection) {
        try {
            BaseVehicle.engineStateTypes engineState = bb.getEnum(BaseVehicle.engineStateTypes.class);
            int quality = bb.getInt();
            int loudness = bb.getInt();
            int power = bb.getInt();
            switch (engineState) {
                case Idle:
                    this.getVehicle().engineDoIdle();
                    break;
                case Starting:
                    this.getVehicle().engineDoStarting();
                    break;
                case RetryingStarting:
                    this.getVehicle().engineDoRetryingStarting();
                    break;
                case StartingSuccess:
                    this.getVehicle().engineDoStartingSuccess();
                    break;
                case StartingFailed: {
                    VehicleEngineStateChangeReason reason = bb.getEnum(VehicleEngineStateChangeReason.class);
                    this.getVehicle().engineDoStartingFailed(reason);
                    break;
                }
                case Running:
                    this.getVehicle().engineDoRunning();
                    break;
                case Stalling:
                    this.getVehicle().engineDoStalling();
                    break;
                case ShuttingDown: {
                    VehicleEngineStateChangeReason reason = bb.getEnum(VehicleEngineStateChangeReason.class);
                    this.getVehicle().engineDoShuttingDown(reason);
                }
            }

            this.getVehicle().setEngineFeature(quality, loudness, power);
        } catch (Exception var8) {
            DebugType.Multiplayer.printException(var8, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        try {
            BaseVehicle.engineStateTypes engineState = this.getVehicle().getEngineState();
            b.putEnum(engineState);
            b.putInt(this.getVehicle().getEngineQuality());
            b.putInt(this.getVehicle().getEngineLoudness());
            b.putInt(this.getVehicle().getEnginePower());
            if (engineState == BaseVehicle.engineStateTypes.StartingFailed || engineState == BaseVehicle.engineStateTypes.ShuttingDown) {
                b.putEnum(this.getVehicle().getEngine().getVehicleEngine().getStateChangeReason());
            }
        } catch (Exception var3) {
            DebugType.Multiplayer.printException(var3, this.getClass().getSimpleName() + ": failed", LogSeverity.Error);
        }
    }
}
