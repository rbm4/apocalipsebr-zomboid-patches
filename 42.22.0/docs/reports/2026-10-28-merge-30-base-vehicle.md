# Review 30/48: BaseVehicle

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. ZombiePacket review 29 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/vehicles/BaseVehicle.java).
- [Old patch](../../../42.21.0/src/zombie/vehicles/BaseVehicle.java).
- [Target vanilla](../../decompiled/zombie/vehicles/BaseVehicle.java).
- [Candidate diff](2026-10-28-merge-30-base-vehicle.diff).

Retain the existing square/model lookup optimizations, server sound shortcut,
idle-device routing and phase telemetry. The merge is clean; all old patch
additions/removals are preserved on the new vanilla baseline. Applied source equals the reviewed candidate after exact vanilla baseline `65ac5ff`. Primary owner: OPT-VEHICLE; shared owners: physics/streaming, vehicle
parts, sound and telemetry.

## Existing optimizations and contracts

During server update, reuse the already queried square/below references when the
final Z equals their levels, instead of making another global square lookup.
Other Z/client cases retain the native lookup. Keep the surrounding position,
chunk bounds and fallback logic. Correctness relies on the same square/position
ownership and lookup timing as the old patch; this is not a new world snapshot.

In server updateTransform, lazily build one identity map from VehiclePart to the
first matching ModelInfo when parent attachments need it, replacing repeated
linear model searches. Rebuild from current models on each relevant call and
clear after normal completion. Preserve first-match semantics via putIfAbsent.
The inherited cleanup is not in finally, so exceptional transform exits can
retain references until another build/clear; this proposal does not silently
redesign that lifetime contract.

In updateSounds, keep updateWorldSounds before returning early for a server
vehicle with no emitter. Client behavior is unchanged. The shortcut avoids
listener-distance work whose remaining server paths require an emitter; it does
not suppress world sounds used by gameplay.

For engine-off server battery/device work, route through
getParts().updateIdleDeviceParts rather than rescanning every vehicle part.
Keep the native client scan, lightbar/battery handling and running-engine logic.
The helper must preserve enabled device/light membership and mutation fallback;
that implementation is the next individual review, VehicleParts (31).

Retain vehicle update stage timing/work counters and final-stage recording on
exceptions/early returns. Telemetry attributes checks/base/auth/physics/impulses/
parts/final work; it is not itself a speedup. Reduced overall vehicle simulation
cadence is owned by the applied scheduler, not introduced by this class.

## Upstream retained

Keep new towing-partner reattachment IDs, wait/abandon timeout behavior, inactive
physics constraint handling, towing-related serialization and changes to impact/
key-drop behavior. Preserve new headlights sound/silent APIs, container query
APIs and other target fields/methods. Do not restore old physics, save or towing
source merely to obtain the optimizations.

## Verification and remaining checks

Source checks passed: zero conflicts and exact old patch added/removed lines
preserved against target vanilla. At proposal time no new target Java/runtime fixture had run. After application,
[extracted target vehicle checks](../../../tools/test_vehicle_parts_port.py)
passed 60,018 assertions using this applied source plus the pending review31
candidate in a temporary source view. Full classes/native runtime were not tested. Existing historical vehicle fixtures are not evidence of target
native compatibility. Applied review31 now supplies VehicleParts.updateIdleDeviceParts. This source
dependency is resolved; complete native/JAR validation remains pending.

ServerVehicleUpdateTelemetry is copied support; VehiclePart invalidation hooks
are staged. Vehicle proximity movement invalidation is also called by the carried
scheduler bucket and spatial helper, not newly added here. Verify the complete
membership/movement contract rather than attributing it to this BaseVehicle diff.

Validate square reuse with Z/streaming transitions, parent-model identity/order,
exception cleanup, emitter/world-sound behavior, device-list mutation, elapsed
part timing, towing/reconnection and native physics/save behavior. Exact-JAR
compilation, JNI/native world and multiplayer runtime checks remain pending.
No installation or database was changed.

User approved: "yes". Applied-candidate equality passed. Review31 now supplies the approved helper; no installed files were changed.
