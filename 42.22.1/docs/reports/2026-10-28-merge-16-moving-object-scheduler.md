# Review 16/48: MovingObjectUpdateScheduler

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. Review 15 IsoCell is applied.
See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/MovingObjectUpdateScheduler.java).
- [Old patch](../../../42.21.0/src/zombie/MovingObjectUpdateScheduler.java).
- [Target vanilla](../../decompiled/zombie/MovingObjectUpdateScheduler.java).
- [Candidate diff](2026-10-28-merge-16-moving-object-scheduler.diff).

Retain the existing persistent server scheduler and activity-based vehicle/animal
cadence. Resolve both conflicts by preserving the old lifecycle/thread ordering
and adding native per-player chasing-zombie statistics in the indexed target pass.
Target source matches the reviewed candidate; exact vanilla baseline commit is `af72c24`.

## Existing optimization and contracts

Vanilla rebuilds scheduling buckets by traversing the active object collection
each frame. Our server path classifies objects through ServerMovingObjectSet,
uses persistent identity-based membership and changes buckets only on addition,
removal or simulation-level changes. It drains removed lifetimes and compacts
holes, including pooled objects removed and re-added under the same reference.
It still checks scheduled members for current activity every frame.

Idle vehicles run at SIXTEENTH cadence; drivers/passengers, engine/activity,
mechanics UI, alarms/sirens/lightbars, towing, motion or animals select FULL.
Active/held/lured/fighting/thumping/alerted/vehicle/hooked animals select HALF;
others select SIXTEENTH. This is existing patch behavior, not native equivalence
for every timer/action. Minimum levels and the client distance/render/FPS path
remain as implemented in the old patch. The enabled flag does not disable the
specialized server cadence.

Before server frame mutation, join previous animation outside the scheduler
monitor to avoid deadlock with postupdate/removal callbacks. Inside the monitor,
wait for zombie-packer workers before culling/classification. Preserve synchronized
update/postupdate/removal, source lifetime checks, bucket cleanup and telemetry.
These contracts depend on the corresponding worker/bucket/collection integrations;
they are not proof all external world reads are thread-safe.

Primary owner: OPT-MOVE. Shared owners: animal/vehicle cadence, collection
lifecycles, asynchronous animation/zombie sending and telemetry.

## New vanilla behavior retained

42.22.0 resets numChasingZombies for players in GameServer.IDToPlayerMap each frame
and counts active zombies targeting non-animal IsoPlayers. The old optimized path
would omit this behavior if copied unchanged.

Reset player counts after native cull preparation, then count targets through
ServerMovingObjectIndex.getPerceptionTargets after index compaction. Keep GUI
zombie updates conditional within that pass. Dedicated servers now require this
target pass even without GUI: O(players + active target entries) per frame.
Do not claim the old no-zombie-target-traversal benefit remains unchanged. Other
bucket rebuild/classification savings remain workload-dependent.

## Dependencies and validation

IsoCell now supplies ServerMovingObjectSet; bucket/index/perception/telemetry
helpers are staged in the target. NetworkZombiePacker.awaitWorkers and later
animal/vehicle integrations still require their own ports. Full target compilation
is not claimed while these dependencies remain pending.

The [candidate fixture](../../../tools/test_moving_object_scheduler_port.py)
reconstructs the candidate in a temporary directory and runs the shared scheduler,
index and bucket fixture using target support sources and engine doubles. It passed
**550 lifecycle/algorithm assertions**, including persistent membership, removals,
reuse, cadence, multiplier restoration, animation synchronization, sound/perception
indexes and new chasing-count reset, animal-target exclusion, removal and GUI guards.
Compiler reported unchecked operations in the carried bucket implementation.
Fixture timings are not server benchmark evidence.

The full game JAR, native animation/world ownership, packet worker integration,
real vehicle/animal behavior and multiplayer chasing statistics remain runtime
validation targets. Only target source and migration records changed; no installation or database
was modified. Applied-candidate equality and all 550 assertions passed again.

User approved: "yes". Approval applies to this class only; review 17 IsoAnimal
remains unapproved.
