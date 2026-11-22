# Review 11/48: LootRespawn

Status: **approved retirement; target vanilla used without a source override**.
The user clarified: "Retire the reader patch only". The broader square-position/
network-ID cache remains in scope. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/LootRespawn.java).
- [Old patch](../../../42.21.0/src/zombie/LootRespawn.java).
- [Target vanilla](../../decompiled/zombie/LootRespawn.java).
- [Machine-readable no-override proposal](2026-10-27-merge-11-loot-respawn.json).

Approved decision: **retire this old replacement and use the target vanilla
LootRespawn directly**, without copying a redundant Java source into the target.
There is no candidate code diff because choosing the new traversal at the sole
conflict produces a complete file identical to target vanilla.

## Evidence and scope

The entire old patch changes one square-object reader to use the audited
`apocbrReadOnlyElements()` path on the server instead of declaring a backing-array
escape through `getElements()`. Upstream now obtains a `List<IsoObject>`, reads
its size and traverses with `objects.get(i)`. It no longer uses that array API.

This is the same obsolete-reader situation as review 10. Upstream makes our
specific audited-reader wrapper unnecessary; it has not implemented the whole
separate identity-position/mutation cache optimization. That adapter remains
explicit later work.

Neither our old patch nor this proposal changes loot respawn timing, container
selection/refill, eligibility checks or gameplay policy. Keep all target native
behavior, including its List traversal and guards. No source override is needed
to preserve a custom behavior because none remains after this adaptation.

## Deployment and validation

Recorded `zombie/LootRespawn.class` and its nested class family in the
[retirement manifest](../../tools/retired-class-families.json). The manifest is
tracking data and is not yet consumed by deployment scripts. Cleanup/restore of
stale old loose overrides must be implemented and verified before deployment;
the game JAR and running installation are not changed during this source port.

Source checks verify the one old patch and full-file equivalence after selecting
target traversal; the proposal pins the native SHA-256. No new gameplay fixture
is required for an omitted custom replacement. Runtime loot respawn and stale
override cleanup remain deployment/integration checks.

Decision applied at source-tracking level only. No installed files were changed.
