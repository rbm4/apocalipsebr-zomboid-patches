# Review 18/48: DesignationZoneAnimal

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. IsoAnimal review 17 is
applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/areas/DesignationZoneAnimal.java).
- [Old patch](../../../42.21.0/src/zombie/iso/areas/DesignationZoneAnimal.java).
- [Target vanilla](../../decompiled/zombie/iso/areas/DesignationZoneAnimal.java).
- [Candidate diff](2026-10-28-merge-18-designation-zone-animal.diff).

Keep cached zone topology, hash-assisted deduplication, spatial zone lookup and
telemetry. Preserve new native Set storage and getAnimalsInZone snapshot API.
Two conflicts resolve by combining imports and using native snapshot iteration
with our seen set in getAnimalsConnected. Applied source matches the reviewed candidate after exact vanilla baseline `fbf8f6f`.

## Existing optimizations

Native connected-zone traversal repeatedly performs linear membership checks.
Our collector uses HashSet membership while retaining its result-list traversal
order. Server calls with an empty initial list and no previous zone can reuse
ServerAnimalZoneTopology. That helper validates the public zone list/geometry
before reusing connectivity, rebuilding when references, ordering or shape change.
It caps cached references and spatial-grid expansion at 65,536 and falls back
when necessary. Validation itself still scans zones; caching is not free.

Zone checks retain the full square/object scan but use seen sets for water squares,
troughs and hutches instead of repeated list contains calls. Server water-neighbor
ownership checks use the validated topology's spatial lookup; clients use native
getZone. Connected corpse/animal result lists retain deduplication via seen sets;
hooked animals remain excluded. Clear topology on Reset and zone splitting.
Keep zone-check, reattachment and topology telemetry.

Primary owner: OPT-ANIMAL; shared owners: zone geometry/lifecycle, animal
membership and telemetry. Recursive connectivity and public mutation/threading
contracts remain inherited limitations, not newly guaranteed safe behavior.

## Upstream Set API and membership retained

Native animals is now Set<IsoAnimal>; addAnimal uses set insertion and
getAnimalsInZone returns a new HashSet snapshot. Preserve that descriptor and
snapshot isolation. Preserve snapshot-based zone detach, trough/hutch
reattachment, metadata work and connected-animal iteration. Do not restore the
old mutable ArrayList getAnimals API or positional animals.get calls. This
matches the already ported IsoAnimal mother-attachment API.

The new native HashSet snapshot provides no stable animal iteration order;
result order follows that upstream iteration. Our seen set only removes duplicate
entries across connected zones and does not invent a stable order.

## Suspicious upstream baby-update loop

Target decompiled doMeta contains an outer loop of isoAnimals.size() iterations
around another traversal of the same snapshot. Each baby therefore appears to
receive updateStatsAway(hours) N times for N animals, while adults update once.
That also appears quadratic. Neither our old patch nor this candidate introduces
this loop. The candidate keeps the entire target doMeta method unchanged.

Treat this as a suspected upstream/decompiler issue: verify against the exact
JAR bytecode or runtime before changing behavior. This proposal does not authorize
a separate baby-stat fix or claim the apparent behavior is confirmed in the game.

## Verification and remaining checks

The [candidate topology fixture](../../../tools/test_animal_zone_port.py)
reconstructs the candidate without creating a replacement source. It passed
**104,003 differential assertions** using extracted native connectivity and
candidate collector methods with the actual target ServerAnimalZoneTopology and
geometry/telemetry doubles. It covers randomized zone layouts, cached results,
geometry/list reordering and mutation, spatial lookups, and oversized fallback.
Source assertions preserve native doMeta, snapshot getter and reAttachAnimal
exactly and reject positional access to the new animal Set.

This fixture does not execute full zone check, water/trough/hutch effects, animal
stats, split/reset integration or JNI/native world calls. Validate snapshot
mutation during detach, duplicate/hooked animal results, cache invalidation,
zone resource checks and the suspicious metadata loop on the real target.
Exact-JAR compilation and native multiplayer/runtime validation remain pending.
No game installation or database was changed.

User approved: "yes". Applied-candidate equality and 104,003 topology assertions
passed again. Native doMeta remains unchanged; its suspected issue remains
tracked for bytecode/runtime verification. Approval applies to this class only.
