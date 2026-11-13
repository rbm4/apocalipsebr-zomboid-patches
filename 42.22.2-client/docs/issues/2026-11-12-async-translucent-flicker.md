# Translucent geometry disappears while asynchronous ordering refreshes

Reported 2026-11-12 after the [schema 5 capture](../reports/2026-11-12-schema-5-capture-analysis.md).
Owning guide: [deferred textures and asynchronous translucent ordering](../features/2026-11-12-client-deferred-textures-and-async-translucency.md).

Status: source mitigation implemented; live visual verification remains pending.

2026-11-13 [schema 6 capture](../reports/2026-11-13-schema-6-capture-analysis.md)
confirms the catch-up runs with very low measured owner preparation wall cost
(19.544 ms total across 4,174 frames). Telemetry does not establish the visual result.

## Cause and correction

Schema 5 intentionally skipped a translucent chunk/player/level pass if its
current list differed from a ready worker result. A new snapshot was submitted,
and the whole pass disappeared until a matching result was available. Moving or
aiming changes candidate/cutaway lists, so repeated misses can produce visible
disappearance/reappearance. A zero PENDING counter did not exclude this behavior:
the initial SUBMITTED call also skipped the pass.

Schema 6 removes that skip. The owner first uses a worker result matching current
references and sort keys, then a matching cached continuity result. If neither
is available, it prepares the current deduplicated, stably sorted list itself
and caches that result. This also covers cold entries, pending obsolete jobs,
queue saturation and worker failure. No worker completion wait is introduced.
At most one worker job remains outstanding per cache entry; the queue/cache
limits and world-change/expiry handling are retained.

The catch-up uses the same identity merge and stable sort as the worker, with
separate ThreadLocal scratch per thread. Source arrays are immutable snapshots;
workers never read game-object fields. The main display snapshot is separate
from an outstanding job so neither thread edits the other's result. A changed
or reused pooled square cannot be rendered from an unchecked old list.

This deliberately trades occasional main-thread sorting for continuous geometry.
The schema 5 capture showed approximately 99.5% reuse, suggesting catch-up should
be uncommon in that workload; it is not a guarantee for continuously changing
scenes. Cost scales with the current candidate lists and must be measured.

The patch does not interpolate alpha or delay vanilla cutaway visibility. It
removes the async-induced blank pass while retaining current game visibility
and ordering. Residual vanilla cutaway transitions may still be noticeable.
Texture preprocessing has no new main-thread fallback.

## Measurement and validation

Schema 6 adds translucentWorker counters:

- OWNER_CATCHUP: current orderings built on the main owner.
- OWNER_CATCHUP_NANOS: their merge/sort work time, excluding snapshot capture.
- CONTINUITY_REUSED: cached current continuity results consumed.

REUSED still counts ready matching worker results; PENDING and QUEUE_FULL now
describe worker scheduling conditions rather than skipped draws. The existing
CELL_TRANSLUCENT_ORDER phase includes owner validation, capture and catch-up.
Worker counters are writer-time samples and do not align exactly with frames.

Production-helper tests cover immediate cold/changed results, stable tie/duplicate
semantics, a blocked worker, saturation, mutation during an obsolete job, caching
the catch-up, world reset and cache bounds. Tests compare identity/order rather
than running a renderer. Exact-JAR DryRun compiles 19 sources / 62 classes.

After reinstalling, test moving and aiming around walls, windows, ground items,
multiple floors, streaming boundaries, split-screen and reconnect. Compare the
new counters and frame costs. Live visual equivalence is not established by the
fixtures; this fix has not been deployed to the installed game. All 10 client
tests pass (63.609 seconds), including disposable installation/revert checks.
