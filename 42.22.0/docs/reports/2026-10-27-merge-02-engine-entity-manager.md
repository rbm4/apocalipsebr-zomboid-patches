# Merge review 02/48: EngineEntityManager

Status: **approved and applied; target removal fixture passed; target-JAR/runtime pending**. Review 01
(`GameEntity`) is approved and applied. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and candidate

- [Old vanilla](../../../42.21.0/decompiled/zombie/entity/EngineEntityManager.java).
- [Old patch](../../../42.21.0/src/zombie/entity/EngineEntityManager.java).
- [Target vanilla](../../decompiled/zombie/entity/EngineEntityManager.java).
- [Applied diff against target vanilla](2026-10-27-merge-02-engine-entity-manager.diff).

The user approved this combination with "yes, proceed". The exact target vanilla
baseline was committed separately as `9d69620`; the approved diff is now applied
to [target EngineEntityManager](../../src/zombie/entity/EngineEntityManager.java).
The behavioral patch remains separate from that baseline commit; no push occurred.
All upstream pending-add changes and the indexed-removal optimization remain.

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

## Verification and remaining checks

Verified for review: all upstream edits remain in the candidate, including the
unconditional removal-flag clear; the new predicate exists in applied GameEntity;
the target Array's identity-removal and unordered tail-swap semantics were read.
The installed source matches the reviewed candidate exactly.

`python -B tools/test_unload_simulation_algorithms.py --version 42.22.0 --engine-only`
passed **1,032,972 assertions**. The fixture compiles the complete target patched
and target vanilla manager classes against engine doubles. It exercises seeded
random immediate/delayed removals, callback tail removal/reinsertion, pending-add
cancellation (including while buckets update), duplicate/absent removal,
immediate/queued RemoveAll, reused/reset operation objects, bucket notification
traces, native add/remove callbacks and membership/removal flags. The new
GameEntity predicate is extracted from target production; Java assertions run
enabled. Array, operation pool and other engine collaborators remain explicit
test doubles, so native integration and real pool behavior still need validation.

The existing full 42.21.0 unload suite also passed **1,495,716 assertions**. Its
default target and coverage are unchanged. The 42.22.0 selector intentionally
requires `--engine-only` until the other world replacements have been ported.

Exact-JAR compilation and runtime unload validation remain integration requirements.
The actual GameEntityManager registration/meta-release path is the next review;
it is not supplied by this fixture's engine doubles.

Next decision: [review 03, GameEntityManager](2026-10-27-merge-03-game-entity-manager.md).
