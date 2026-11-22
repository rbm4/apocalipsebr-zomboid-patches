# Merge review 01/48: GameEntity

Status: **approved by the user and applied; full-class compilation/runtime pending**.
The user requested individual
merge review after staging unchanged-baseline and custom classes. Review the
[subsystem tracker and queue](../features/2026-10-27-subsystem-port-tracker.md)
for the current checkpoint. Approval of this class does not approve later merges.

## Inputs and proposed result

- [Old vanilla GameEntity](../../../42.21.0/decompiled/zombie/entity/GameEntity.java).
- [Old patched GameEntity](../../../42.21.0/src/zombie/entity/GameEntity.java).
- [Target vanilla GameEntity](../../decompiled/zombie/entity/GameEntity.java).
- [Proposed additions against target vanilla](2026-10-27-merge-01-game-entity.diff).

The proposal was generated with old patched / old vanilla / new vanilla as a
three-way merge. It has zero textual conflicts. The user approved this exact
combination with "yes apply and move to the next one". The diff is now applied to
[target GameEntity](../../src/zombie/entity/GameEntity.java). The repository's
baseline tool first committed only the exact target vanilla file in `5456a51`;
the behavioral patch remains a separate working-tree change. No push occurred.
Old vanilla and copied support files remain unchanged.

## Vanilla and our existing behavior

In 42.21.0, `usingPlayer` is a private entity reference changed by its setter,
reset and network synchronization. Vanilla's using-player update system visits
the entire entity bucket to discover users and clear those who are dead, on
another floor or outside the ten-tile bounds.

Our patch adds the package-visible, debug-nonrecursive
`ServerUsingPlayerIndex.Member usingPlayerMemberships` chain. Every assignment or
clear of `usingPlayer` notifies `ServerUsingPlayerIndex.changed(this)`, including
the reset and client/server receive paths. The index can then select active
entities in current bucket-position order without discovering them through a
full scan; it retains conservative handling for subclasses overriding accessors.
`EntityBucket`, `UsingPlayerUpdateSystem` and `ServerUsingPlayerIndex` are already
copied, but their subsystem remains incomplete without this integration.

The patch also reports `COMPONENT_SKIPPED` and `COMPONENT_LOAD_FAILED` through
`ApocBRPlayerLoadQuality` when component loading would otherwise omit state or
log an exception. These diagnostics support relational capture and migration
quality checks; they do not replace native component loading or exception handling.
This is why a seemingly optimization-only class also belongs to DB-PLAYER.

## What 42.22.0 changes

The complete vanilla delta is three edits:

1. `isAddedToEngine()` becomes `isAddedOrScheduledForEngine()`, returning
   `addedToEngine || scheduledDelayedAddToEngine`.
2. `reset()` uses that broader predicate before unregistering, so an entity
   scheduled for delayed addition also crosses cleanup.
3. After unregistration, `reset()` asserts `!addedToEngine`.

These changes are part of the pending-add/removal fix implemented across
`EngineEntityManager` and `GameEntityManager`. Those two classes are reviews
02 and 03, not implicitly approved by this review.

## Proposed merge decision

Keep all three upstream changes exactly as supplied. Reapply the existing
membership field and all seven index notifications, plus the two load-quality
diagnostics, at the same semantic points. Preserve native packet layout,
using-player validation and component-load control flow. Do not retain an
obsolete `isAddedToEngine()` alias unless a separately verified consumer needs it.

This first merge therefore retains both the optimized using-player discovery and
persistence quality reporting while adopting upstream pending-add cleanup. No
new algorithm or gameplay policy is proposed.

## Validation and dependent work

The installed source matches the approved candidate, preserving all upstream
changes, seven index notifications and two component-load diagnostics.

`python -B tools/test_using_player_algorithms.py --version 42.22.0` passed
190,141 assertions. The fixture reads target production index/bucket/system
sources and extracts the target GameEntity setter, reset, receive method and
new membership predicate. It compares traversal with target vanilla and tests
callback mutations, shared memberships, reset and packet assignment. Five new
assertions cover pending-add reset unregistering exactly once and clearing the
pending/user state. Java assertions run enabled. Engine dependencies remain
explicit stubs: this is not full GameEntity compilation or a native-runtime test.

The same fixture with `--version 42.21.0` passed 190,136 assertions, preserving
the old target's coverage. No exact-JAR compilation or runtime validation is
claimed while other replacement classes remain unported.

Remaining integration work:

- In subsequent entity reviews, check pending-add cancellation, removal flags,
  bucket notifications and meta release with the index hooks present.
- Extend integration coverage to the actual pending-operation manager as the
  next entity replacements are ported. Its cancellation behavior is not supplied
  by the current GameEntity fixture's unregister stub.
- Verify component load failures reach quality reporting without losing native
  exception behavior. Perform exact-target JAR compilation at the integration
  checkpoint when required replacement classes are present.

Review 01 is complete at the source/fixture checkpoint. The next individual
decision is [review 02: EngineEntityManager](2026-10-27-merge-02-engine-entity-manager.md).
