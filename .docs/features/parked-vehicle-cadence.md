# Parked vehicle cadence

The parked vehicle cadence ports the narrow vehicle classification from the
legacy 42.22.2 moving-object scheduler. It is enabled by default. Set
`-Dapocbr.optimization.parkedVehicle.enabled=false` before startup to restore
vanilla classification. Vanilla chooses full cadence for dedicated-server
moving objects that reach this selector.

`ParkedVehicleModule` advises the original private
`MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject`
method. Only a vehicle selected at full cadence by the dedicated server is
eligible. The hook uses the existing staggered `SIXTEENTH` bucket, with
the normal preupdate, frame step, update and postupdate on that bucket's
tick. Vanilla rebuilds its buckets every frame, so a vehicle that becomes
active before the next classification returns to full cadence that frame.
The agent retains no vehicle references and requires no unload or shutdown
hook.

Drivers, other occupants, animals, mechanics, a non-idle engine, alarms,
lightbar activity, headlights, brake lights, part updates and both towing
directions retain full cadence. The port also retains full cadence for
pending towing reattachment and non-parked velocity on a dedicated server
even when the local physics controller is absent. Headless servers do not
create a local controller for every vehicle, and the vanilla physics packet
can write velocity independently of the controller.

The legacy persistent object index, animal cadence and vehicle-parts
optimization are separate changes. The original object walk and vanilla
bucket membership lifecycle still execute on every frame.

Telemetry reports `vehicles.scheduler.sixteenthChecks` and
`vehicles.scheduler.fullChecks` as classification decisions. These are
vehicle-frame counts. When detailed moving-object telemetry is enabled, use
the vehicle callback sample counts and elapsed time to measure executions.
Compare `simulation.movingObjects.startFrame` with the update and postupdate
phases to account for the cost of checking eligibility.

See the [paired original-JAR test](../validation/server-integration.md#parked-vehicle-cadence-comparison)
for matched workload, protection checks and timing evidence. The headless
fixture leaves connected driving, towing and modded vehicle behavior for
live validation.

## Local results

Two paired runs passed on Windows against the original 42.22.2 JAR. In the
final run, the three warm cycles held 138, 142 and 158 distinct vehicles.
Each arm had 14,016 vehicle-frame opportunities across those cycles and
the native vehicle update count went from 14,016 to 1,296. The test also
exercised the generation cycle, cell unload, saved-chunk reload and save drain.

| Wall time, median warm 32-frame window total per arm | Vanilla cadence | Parked cadence |
| --- | ---: | ---: |
| Native vehicle update bodies | 27.98 ms | 4.40 ms |
| Scheduler setup | 2.81 ms | 7.92 ms |
| Scheduler setup, update and postupdate | 77.20 ms | 49.97 ms |

These numbers come from the test's timed scheduler windows and include its
callback telemetry overhead. The scheduler totals include the vehicle
callback costs. They do not measure a reduction in whole tick or streaming
stall time.

A separate smoke run with the startup flag disabled passed the normal
streaming lifecycle checks and shut down cleanly. The transformed
scheduler evidence contained the telemetry hooks and no cadence invocation.
