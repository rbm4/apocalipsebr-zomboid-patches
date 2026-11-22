# Review 10/48: LoadGridsquarePerformanceWorkaround

Status: **source retirement approved and recorded; target vanilla selected; deployment cleanup pending**.
Reviews 01-09 are approved and applied. See the
[tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and concrete proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/LoadGridsquarePerformanceWorkaround.java).
- [Old patch](../../../42.21.0/src/zombie/LoadGridsquarePerformanceWorkaround.java).
- [Target vanilla](../../decompiled/zombie/LoadGridsquarePerformanceWorkaround.java).
- [Machine-readable no-override proposal](2026-10-27-merge-10-load-gridsquare.json).

The user approved retirement and continuation to the next review. The target has
**no replacement Java file for this class**: use its 42.22.0 implementation from
the game JAR. No baseline commit, redundant vanilla copy or source deletion was
needed because the target override was never copied. There is no code diff:
the complete resolved class equals target vanilla.

## Why no custom patch remains

The entire old patch changes one reader from `getElements()` to the audited
server `apocbrReadOnlyElements()` path, avoiding a backing-array escape marker.
It does not change loot generation, container exploration or overlay behavior.

Upstream now obtains a List, reads its size and uses `objects.get(i)`. The old
array API is absent. Resolving the single conflict with that target traversal
produces a file textually identical to the complete supplied target vanilla file.
There are no remaining custom statements to carry over for this class.

Keep vanilla's ItemPicker begin/end, world-item exclusion, server/client guards,
container fill/explored synchronization and overlay updates. Retiring this
obsolete reader wrapper does not retire the separate identity/mutation cache
adapter still required elsewhere in the migration.

## Deployment dependency to retain

The current target deployment scripts copy compiled class files; ordinary apply
does not prune every class omitted from the target source tree. A preexisting
42.21.0 loose `zombie/LoadGridsquarePerformanceWorkaround*.class` family could
therefore shadow the new JAR even when no target source is present. Source-based
revert discovery must not be assumed to find an omitted source family either.

The approved family is recorded in the
[retirement manifest](../../tools/retired-class-families.json), including nested
classes. This manifest is tracking data: the current scripts do not consume it
automatically. Implement and verify backup/cleanup or restoration in the later
deployment migration before shipping. No installed files, launch settings or
running game were touched. This checkpoint completes source retirement only,
not a deployment-ready guarantee.

## Validation

Source comparison verifies that the only old patch is the audited raw reader and
that the resolved new file equals target vanilla. The proposal records its SHA-256
for reproducibility. No gameplay fixture or full Java build is needed to execute
a nonexistent custom replacement; runtime loot/overlay checks and deployment
cleanup validation remain part of the full target handoff.

Next decision: [review 11, LootRespawn](2026-10-27-merge-11-loot-respawn.md).
