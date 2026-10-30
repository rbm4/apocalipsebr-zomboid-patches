# Review 26/48: PathFindBehavior2

Created 2026-10-28. Status: **approved/applied on 2026-10-28**. IsoGameCharacter review 25
is applied. See the [tracker](../features/2026-10-27-subsystem-port-tracker.md).

## Inputs and proposal

- [Old vanilla](../../../42.21.0/decompiled/zombie/pathfind/PathFindBehavior2.java).
- [Old patch](../../../42.21.0/src/zombie/pathfind/PathFindBehavior2.java).
- [Target vanilla](../../decompiled/zombie/pathfind/PathFindBehavior2.java).
- [Candidate diff](2026-10-28-merge-26-path-find-behavior.diff).

Add the existing isPathLengthGreaterThan helper to target vanilla. Three-way
merge is clean. getPathLength, update(float) and initPathFindRequest stay exactly
native. Applied source equals the reviewed candidate after exact vanilla baseline `cca1b6d`. Primary owner: OPT-PATH;
shared consumer: NetworkZombieAI path-length threshold decisions (review 28).

## Optimization and limits

The old caller computes the full remaining path length merely to compare it
with 5. The helper accumulates the same 2D segments and stops as soon as length
is strictly greater than the requested threshold. Short paths still require a
full traversal; long paths exceeding a small threshold can visit fewer nodes.
Null/empty/exhausted paths defer to native getPathLength. Keep strict comparison,
existing float accumulation, and server path-node-visited telemetry.

It changes only a boolean query, not route generation, path following or actual
path length. No caller benefit is active until the corresponding zombie-AI port
uses it. This preserves the old helper rather than adding cached path lengths or
mutation tracking. Concurrent path changes and malformed/non-finite node geometry
are not covered by the equivalence guarantee: an early answer could differ from
a full sum if a later segment introduces NaN. Validate geometry/ownership in the
real target; no new behavior repair is included in this proposal.

## Upstream retained

Keep initPathFindRequest(avoidFarmingPlants), the native request's new avoidance
argument and finder flag, initial avoidance selected through IsoPlayer.isPlayer,
and the retry without farming avoidance when that initial search fails. Retain
both native/fallback pathfinder routes, waiting-state handling and walking-on-
the-spot reset. Do not restore the old inlined request setup.

## Verification and remaining checks

The [candidate fixture](../../../tools/test_path_find_port.py) reconstructs the
candidate without creating an override. It passed **14,003 assertions** using
extracted native length and candidate threshold methods with path/position/
telemetry doubles. Cases cover finite randomized paths, empty/null/exhausted
paths, zero/negative/finite/infinite/NaN thresholds and early-exit node count.
Source checks preserve native length, request initialization and update methods.
The fixture does not execute route requests, farming collision, JNI/native
pathfinding, zombie packets or concurrently modified paths.

NetworkZombieAI adoption is a later individual review. Exact-JAR compilation,
real request/retry behavior, packet decisions and native runtime validation
remain pending. No installation or database was changed.

User approved: "yes". Applied-candidate equality and 14,003 finite-path threshold
assertions passed again. Native request/runtime integration remains pending.
No installed files were changed.
