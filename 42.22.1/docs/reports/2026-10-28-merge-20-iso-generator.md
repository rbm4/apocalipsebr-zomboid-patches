# Review 20/48: IsoGenerator

Created 2026-10-28. Status: **approved/retired to target vanilla on 2026-10-28**. IsoThumpable
review 19 is applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/objects/IsoGenerator.java).
- [Old patch](../../../42.21.0/src/zombie/iso/objects/IsoGenerator.java).
- [Target vanilla](../../decompiled/zombie/iso/objects/IsoGenerator.java).
- [Machine-readable proposal](2026-10-28-merge-20-iso-generator.json).

Approved retirement of this redundant replacement and using target vanilla directly.
No candidate diff is needed: choosing native traversal at the sole conflict
produces a complete file identical to target vanilla. No source override exists. The user approved: "yes retire and retain native".

## What the old patch did

The only custom change is in updateFridgeFreezerItems(IsoGridSquare): server
traversal reads apocbrReadOnlyElements instead of exposing the backing array
through getElements. That prevented an audited reader from disabling the old
PZArrayList position/ID caches. It did not change generator fuel, condition,
power range, fridge/freezer updates or gameplay policy.

Target vanilla gets List<IsoObject>, snapshots its size and reads objects.get(i).
The old array-exposure helper is unnecessary, as in retired reviews 10-11. This
retires only the reader patch. The separate square-position/network-ID cache
adapter remains in scope, and IsoCell's every-frame generator exception remains.

## Upstream retained

Use the native new List traversal, OnElectricityChanged events after local and
received activation changes, and syncIsoObjectReceive(ByteBufferReader,
UdpConnection) signature. A redundant override is not needed to retain these
changes. Primary owner remains INTEGRATION, shared with electricity, inventory
and network object synchronization.

## Deployment and validation

Recorded zombie/iso/objects/IsoGenerator.class and its nested class family
in the [retirement manifest](../../tools/retired-class-families.json). The manifest
is not consumed by deployment scripts yet. Stale loose-class backup/removal or
restoration must be implemented and verified before deployment; merely omitting
the source does not remove previously installed overrides. Do not change the game
JAR or running installation during this source migration.

Source checks confirm the one old reader change, one conflict, and complete
native-source equivalence after choosing target traversal. The proposal pins the
native SHA-256. No Java gameplay fixture was needed for an omitted override.
Actual electricity events, fridge/freezer behavior, network synchronization and
stale-class cleanup remain deployment/native integration checks.

Source retirement is recorded; native 42.22.0 supplies the class. The broader
square cache remains in scope. No installed files were changed.
