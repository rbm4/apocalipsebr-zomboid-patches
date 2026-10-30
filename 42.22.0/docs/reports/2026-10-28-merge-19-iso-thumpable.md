# Review 19/48: IsoThumpable

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 18 is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/objects/IsoThumpable.java).
- [Old patch](../../../42.21.0/src/zombie/iso/objects/IsoThumpable.java).
- [Target vanilla](../../decompiled/zombie/iso/objects/IsoThumpable.java).
- [Candidate diff](2026-10-28-merge-19-iso-thumpable.diff).

Keep the one added apocbrNeedsServerUpdate predicate on the complete target
vanilla class. The three-way merge is clean. All other target source, including
update(), stays identical. Applied source equals the reviewed candidate after exact vanilla baseline `70adec5`.
Primary owner: OPT-WORLD; integration owner: IsoCell object-update scheduling.

## Optimization and caller contract

IsoCell review 15 already calls this predicate on the server for exact-class
IsoThumpable objects at their assigned update phase. Subclasses bypass the gate;
clients retain native update execution. The predicate does not throttle doors or
change interaction/forcing methods by itself.

If lifeLeft is not greater than -1, server update has no light/fuel work. If the
light is off, only accumulator/timestamp reset can be pending. If it is on, native
fuel handling only runs when the absolute difference between current world-age
minutes and the stored timestamp is strictly greater than 10. Preserve those
conditions and calculations, including the original timestamp units despite the
lastUpdateHours field name. Actual updates still run the untouched native method.

This avoids no-work update calls, including their object-index lookup. It does
not make the whole object list traversal disappear or replace native light/fuel
semantics. Applying this source supplies the missing predicate dependency from
IsoCell; it does not complete all target integrations or compilation.

## Upstream retained

Retain GridSquareEdgeElement and IForceableDoor interfaces; the isNorth rename,
isOpen and setOpen methods; the new syncIsoObjectReceive(ByteBufferReader,
UdpConnection) descriptor and matching super call; updated curtain direction
calls; and all new forcing/struggle/damage/validation behavior. Do not restore
getNorth or the old one-argument network receive signature.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_thumpable_port.py) reconstructs the
candidate in a temporary directory. Source assertions prove the entire file is
exactly target vanilla plus the predicate/comment and that update() is unchanged.
It passed **200,000 differential assertions across 50,000 generated cases** using
the extracted native server update body and candidate predicate with explicit
clock/light-state doubles. Cases cover fuel values including NaN, light state,
accumulator/timestamp state, detached object index and elapsed time. Gated and
ungated execution match fuel/state/write effects in these cases.

The fixture excludes the client visual-light branch and does not execute full
native object linkage, world lighting, network synchronization or door forcing.
The caller's exact-class/server guards were covered with doubles by the IsoCell
cadence fixture. Exact-JAR compilation and native client/server validation remain
pending. No installation or database changes were made.

User approved: "yes". Applied-candidate equality and 200,000 assertions passed
again, along with 1,592 IsoCell cadence assertions. The predicate source dependency
is now supplied. Full native/JAR integration remains pending.
