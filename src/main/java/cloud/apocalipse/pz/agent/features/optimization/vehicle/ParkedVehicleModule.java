package cloud.apocalipse.pz.agent.features.optimization.vehicle;

import cloud.apocalipse.pz.agent.HookCheck;
import cloud.apocalipse.pz.agent.config.AgentConfiguration;
import cloud.apocalipse.pz.agent.features.optimization.OptimizationModule;
import cloud.apocalipse.pz.agent.features.telemetry.OptimizationTelemetry;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Assigns parked vehicles to the original scheduler's staggered bucket on dedicated servers. */
public final class ParkedVehicleModule extends OptimizationModule {
    private static final String TARGET = "zombie.MovingObjectUpdateScheduler";
    private static final String METHOD = "getUpdateSchedulerSimulationLevelForObject";
    private static final String DESCRIPTOR = "(Lzombie/iso/IsoMovingObject;F)Lzombie/UpdateSchedulerSimulationLevel;";

    public ParkedVehicleModule() {
        super("parked-vehicle-cadence", AgentConfiguration.PARKED_VEHICLE_OPTIMIZATION);
    }

    @Override public List<HookCheck> hookChecks() {
        return List.of(new HookCheck("parked-vehicle-cadence", TARGET, METHOD,
            ParkedVehicleModule.class.getName(), "selectLevel", false));
    }

    @Override public AgentBuilder configure(AgentBuilder builder) {
        if (disabledReason() != null) return builder;
        return builder.type(named(TARGET)).transform((dynamic, type, loader, module, domain) ->
            dynamic.visit(Advice.to(Cadence.class).on(named(METHOD).and(hasDescriptor(DESCRIPTOR)))));
    }

    public static final class Cadence {
        @Advice.OnMethodExit
        public static void exit(@Advice.Argument(0) IsoMovingObject object,
                                @Advice.Return(readOnly = false) UpdateSchedulerSimulationLevel level) {
            if (GameServer.server && object instanceof BaseVehicle vehicle) {
                level = selectLevel(vehicle, level);
            }
        }
    }

    public static UpdateSchedulerSimulationLevel selectLevel(BaseVehicle vehicle, UpdateSchedulerSimulationLevel original) {
        if (original != UpdateSchedulerSimulationLevel.FULL) return original;
        boolean parked = isParked(vehicle);
        OptimizationTelemetry.count(parked ? OptimizationTelemetry.Counter.VEHICLE_PARKED_CADENCE
            : OptimizationTelemetry.Counter.VEHICLE_FULL_CADENCE);
        return parked ? UpdateSchedulerSimulationLevel.SIXTEENTH : original;
    }

    private static boolean isParked(BaseVehicle vehicle) {
        if (vehicle.isRemovedFromWorld()
            || vehicle.getID() < 0
            || vehicle.getScript() == null
            || vehicle.getCurrentSquare() == null
            || vehicle.getDriver() != null
            || vehicle.isMechanicUIOpen()
            || vehicle.getEngineState() != BaseVehicle.engineStateTypes.Idle
            || vehicle.needPartsUpdate()
            || vehicle.isAlarmActive()
            || vehicle.lightbarLightsMode.isEnable()
            || vehicle.lightbarSirenMode.isEnable()
            || vehicle.getHeadlightsOn()
            || vehicle.getStoplightsOn()
            || vehicle.getVehicleTowedBy() != null
            || vehicle.getVehicleTowing() != null
            || vehicle.isWaitingToReattachTowingPartner()
            // Dedicated servers can receive velocity updates without a local physics controller.
            || !hasParkedVelocity(vehicle)
            || !vehicle.isAtRest()
            || !vehicle.getAnimals().isEmpty()) {
            return false;
        }
        int seats = vehicle.getMaxPassengers();
        for (int seat = 0; seat < seats; seat++) {
            if (vehicle.getCharacter(seat) != null) return false;
        }
        return true;
    }

    private static boolean hasParkedVelocity(BaseVehicle vehicle) {
        var velocity = vehicle.jniLinearVelocity;
        return velocity.x * velocity.x + velocity.z * velocity.z < 0.025F * 0.025F && Math.abs(velocity.y) < 0.5F;
    }
}
