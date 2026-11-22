# Review 31/48: VehicleParts

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. BaseVehicle review 30 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/vehicles/VehicleParts.java).
- [Old patch](../../../42.21.0/src/zombie/vehicles/VehicleParts.java).
- [Target vanilla](../../decompiled/zombie/vehicles/VehicleParts.java).
- [Candidate diff](2026-10-28-merge-31-vehicle-parts.diff).

Keep the existing sparse idle-device index, revision invalidation and callback-
aware traversal on target vanilla. Merge is clean. Preserve native List-based
vehicle container query descriptors. Applied source equals the reviewed candidate after exact vanilla baseline `0c6bcc0`.
Primary owner: OPT-VEHICLE; shared owners: parked vehicle updates, part capability
lifecycle, time-based updates and telemetry.

## Optimization and lifecycle contract

BaseVehicle's applied engine-off server path calls updateIdleDeviceParts. Build
an ordered int-index list of parts with DeviceData or a light; subsequent stable
updates visit only those candidates, reading activation live. Parts with neither
capability need no repeated visit. Disabled devices remain candidates so enabling
one is visible without rebuilding; capability creation/replacement must invalidate.

Retain dirty/revision tracking on add and clear, plus the already copied
VehiclePart invalidation hooks around capability creation/replacement. Rebuild
on the next use after invalidation. If a callback changes parts/capabilities,
resume from the next native list index using binary search in the rebuilt sparse
index, rather than continuing stale candidate indices. Preserve original order
and the inherited cursor behavior, including callback removal/append effects.
This requires mutations to use the observed lifecycle paths; arbitrary external
mutation/concurrent access is not made safe by the cache.

Only active device or light candidates invoke updatePart. Preserve native
updatePart's time gating and elapsed-time calculations, actual device/Lua work,
client behavior and BaseVehicle's lightbar/battery handling. Record rebuild,
entries/candidates and update-attempt counters through the existing telemetry
batch contract, including checked/updated counts in finally after exceptions.

Applying this source supplies BaseVehicle.updateIdleDeviceParts' source
requirement. It does not establish full vehicle runtime/JAR compatibility.

## Upstream retained

Keep getVehicleItemContainers returning/accepting List<ItemContainer>, allocated
as ArrayList, and PZArrayUtil.addUniqueReference to preserve reference uniqueness.
Do not restore removed PZArrayList descriptors or addUniqueReference instance
calls. The index does not change this query or native updatePart behavior.

## Verification and remaining checks

The [target vehicle fixture](../../../tools/test_vehicle_parts_port.py) builds a
temporary source view containing applied BaseVehicle, copied target VehiclePart/
telemetry, exact native target references and this unapplied VehicleParts candidate.
It passed **60,018 assertions** using extracted production methods and engine/
callback doubles, covering device/light selection, capability invalidation,
callback mutation and exceptions, sparse no-capability work, elapsed minute
updates/cooling, square reuse, model first-identity lookup and telemetry batching.
It also verifies native matrix/sound statements outside the reviewed optimizations
and VehiclePart source outside its capability hooks. No target replacement is
created by this fixture.

These are extracted-method checks, not complete vehicle classes. Actual Lua
callbacks, part networking, native models/physics, battery/radio/light behavior,
streaming/towing lifecycle and exact-JAR compilation remain integration targets.
No installation or database was changed.

User approved: "yes". Applied-candidate equality and 60,018 extracted vehicle
assertions passed again. BaseVehicle helper source dependency is supplied; full
native integration remains pending. No installed files were changed.
