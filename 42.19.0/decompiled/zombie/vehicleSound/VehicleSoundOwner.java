// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.vehicleSound;

import zombie.audio.BaseSoundEmitter;
import zombie.audio.parameters.ParameterVehicleRoadMaterial;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.LightbarSirenMode;

public interface VehicleSoundOwner {
    float getX();

    float getY();

    float getZ();

    boolean isListenerInRange(float var1);

    String getScriptName();

    VehicleScript getScript();

    int getEngineCondition();

    BaseVehicle.engineStateTypes getEngineState();

    boolean isEngineRunning();

    boolean isEngineSounding();

    double getEngineSpeed();

    int getTransmissionNumber();

    float getCurrentSpeedKmHour();

    boolean isAlarmSounding();

    boolean isBrakePedalPressed();

    boolean isGasPedalPressed();

    ParameterVehicleRoadMaterial.Material getRoadMaterial();

    String getChosenAlarmSound();

    boolean isBackupBeeperSounding();

    boolean isDoorAlarmSounding();

    boolean isHornSounding();

    BaseSoundEmitter getEmitter();

    boolean isAnyListenerInside();

    boolean isSirenSounding();

    LightbarSirenMode getLightbarSirenModeObject();

    float getMaxWheelSteering();

    float getMinWheelSkid();

    boolean isAnyTireMissing();
}
