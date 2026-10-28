# Merge review 02/48: EngineEntityManager

Status: **awaiting individual user discussion; not applied**. Review 01
(`GameEntity`) is approved and applied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/EngineEntityManager.java).
- [Old patch](../../../42.21.0/src/zombie/entity/EngineEntityManager.java).
- [Target vanilla](../../decompiled/zombie/entity/EngineEntityManager.java).
- [Unapplied candidate diff against target vanilla](2026-10-27-merge-02-engine-entity-manager.diff).

The three-way merge is textually clean. No target replacement or baseline commit
has been created for this class. The candidate preserves all upstream pending-add
changes and adds back the old indexed-removal optimization and its measurements.

## Our optimization versus vanilla

Vanilla stores entities in an unordered `Array` and removes one with
`removeValue(entity, true)`. This scans by identity, then swaps the tail into the
removed slot. Repeated removals during unload can repeatedly scan the same array.

Our `IdentityHashMap<GameEntity, Integer> entityPositions` records the slot on
actual insertion. Removal finds that slot directly, uses `removeIndex`, then
updates the swapped tail's slot. The target `entity/util/Array.java` still gives
the same tail-swap result for this unordered array. Expected slot lookup work is
constant rather than linear; this is not a measured 42.22.0 speedup.

Bucket updates, component-handler detachment, flags and removal listeners retain
their native order. The map update occurs before callbacks, allowing callbacks
to remove the swapped tail or reinsert a removed identity. Existing telemetry
records removal time, array work and indexed-removal count. It remains secondary
verification work and does not change the scheduling policy.

## What 42.22.0 fixes

1. Delayed addition tests the new `isAddedOrScheduledForEngine()` predicate and
   no longer sets `addedToEngine` before actual insertion.
2. Removing an entity still awaiting addition cancels and returns its queued
   operations to the pool, then clears `scheduledDelayedAddToEngine`. It does
   not attempt removal from an array the entity has not entered.
3. `removeEntityInternal` clears `scheduledForEngineRemoval` even when the
   entity was absent from the set. That assignment must stay outside `if (removed)`.

These changes complement the applied GameEntity reset/predicate update. The
broader GameEntityManager registration/meta-release integration is review 03.

## Proposed decision

Keep the new lifecycle behavior unchanged and retain our indexed tail-swap
removal. Populate the position map only in `addEntityInternal`; cancellation of
a pending add must not create or remove a map entry. Enter indexed removal only
after the native set reports an actual member. Preserve the unconditional
removal-flag clear, callbacks and native pool/queue behavior.

The candidate does exactly this; it does not restore the old premature
`addedToEngine` flag. Keeping the original `GameEntity` predicate alias is not
necessary for this target implementation.

## Verification and next checks

Verified for review: all upstream edits remain in the candidate, including the
unconditional removal-flag clear; the new predicate exists in applied GameEntity;
the target Array's identity-removal and unordered tail-swap semantics were read.
No candidate code has been installed or executed.

If accepted, adapt the engine-removal fixture to the new target and compare
against the exact new vanilla manager. Include immediate/delayed add/remove,
pending-add cancellation, duplicate/absent removal, RemoveAll, pool/queue reuse,
callback tail removal/reinsertion and flag/listener order. The old harness in
`tools/test_unload_simulation_algorithms.py` is reference coverage only until
adapted; it still targets 42.21.0. Exact-JAR compilation and runtime unload
validation remain integration requirements.

Decision requested: apply this candidate to EngineEntityManager only, preserving
the upstream lifecycle fixes alongside the indexed-removal optimization.
