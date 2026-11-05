# Review 15/48: IsoCell

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Reviews 01-09 and 12-15
are applied; 10-11 are retired. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/iso/IsoCell.java).
- [Old patched source](../../../42.21.0/src/zombie/iso/IsoCell.java).
- [Target vanilla](../../decompiled/zombie/iso/IsoCell.java).
- [Candidate diff](2026-10-28-merge-15-iso-cell.diff).

Applied merge keeps the old optimization/integration hooks on the new vanilla class.
The three-way merge is clean. This is source applicability, not runtime safety
or a guarantee that the current incomplete target tree compiles.

## Optimizations and behavioral tradeoffs

1. Spread regular processIsoObject updates across frames. The default
   apocbr.isoObjectUpdateFrameMod=10 assigns an object to one of ten phases,
   hashing its entity network ID or falling back to identity hash when unavailable.
   This reduces update calls, but still scans the processing list every frame.
   It sets perObjectMultiplier=10 for those updates; compensation only affects
   code using the corresponding GameTime multiplier, not arbitrary callbacks.
   IsoTrap and IsoGenerator (including subclasses) update every frame with
   multiplier 1. The outer finally restores multiplier 1 even after exceptions.
   Phasing applies on clients/single-player too; only some gates are server-only.
2. On the server, skip exact-class IsoThumpable updates when its old
   apocbrNeedsServerUpdate predicate says there is no pending light/fuel work.
   Subclasses retain their own update behavior. This depends on review 19.
3. Use ServerMovingObjectSet for active moving objects. It classifies membership
   on insertion/removal and maintains indexed animal, scheduling and perception
   collections for other subsystems, avoiding repeated full-world classification.
   Its collection contract rejects null; it is not thread-safe merely because
   it tracks membership. Scheduler integration remains review 16.
4. Use MutationTrackedArrayList for zombies and a tracked vehicle set, with one
   ServerPlayerSpatialQueries instance per cell. These allow spatial-query cache
   invalidation and support later player-query ports. They do not make every
   operation on these collections faster. This tracked zombie list is separate
   from the still-deferred square-object List adapter.
5. Use the indexed animal collection for server sound work and phase it by animal
   ID (default apocbr.animalSoundFrameMod=4), retaining the on-hook exclusion.
   Keep ordinary client traversal. Skip the cell-level zombie vocal loop on
   servers. These intentionally alter update cadence rather than proving every
   sound callback equivalent to native per-frame processing.
6. Retain timing/counter scopes for items, rooms, object updates, static updaters,
   corpses, fishing and per-kind IsoObject update costs.

Primary owner: OPT-WORLD. Shared owners: moving-object scheduling, animal sounds,
player spatial queries, tracked collection lifecycles and telemetry.

## Upstream retained

Keep target rendering/stencil constants and calculations. Keep removal of the
old Core.lastStand condition in spotted-room handling. The old patch does not
modify these areas. Do not restore their old vanilla implementations.

## Source checks, dependencies and remaining validation

Three-way merge completed with zero conflicts. Source assertions confirm all
added/removed patch lines match the old patch intent exactly, and the three
modified object-processing methods match their old patched bodies. This retains
known behavior and limitations rather than redesigning the algorithms silently.
The [target cadence fixture](../../../tools/test_iso_cell_port.py) passed 1,592
assertions across cadence mods 1, 10 and 17 using extracted production scheduling
methods and engine/update/gate doubles. It covers pending removals, self-removal,
client gate bypass, regular/every-frame cadence and exception multiplier restoration.
The real IsoThumpable predicate and full collection/native integration are not
executed by this fixture.

Review 19 is now approved/applied and supplies IsoThumpable.apocbrNeedsServerUpdate.
This source dependency is resolved; complete compilation still requires remaining ports. Scheduler, animal and
player ports remain separate individual reviews. Copied index/telemetry helpers
are staged support, not a claim that their full integration has been verified.

Remaining integration validation targets: phase distribution and ID changes;
object removal during update; trap/generator cadence; elapsed-time compensation;
exception restoration; no-work gate correctness; moving/zombie/vehicle collection
mutation and cache invalidation; client/server sound behavior. The inherited
frame expression uses remainder rather than floorMod, so negative frame-counter
rollover also needs explicit handling/verification. A clean merge does not resolve
these risks. Full target-JAR compilation/native runtime checks remain pending.

User approved: "yes". Exact vanilla baseline commit is `e150592`; applied source
matches the reviewed diff. No installed files were changed.
