# Deferred textures, asynchronous translucent ordering and building lookups

Measured follow-up: [schema 5 capture analysis](../reports/2026-11-12-schema-5-capture-analysis.md).
Current schema 6 fixes [translucent flicker during refresh](../issues/2026-11-12-async-translucent-flicker.md).
Measured 2026-11-13: [schema 6 continuity costs and remaining stalls](../reports/2026-11-13-schema-6-capture-analysis.md).
Practical follow-up: [remaining translucent work and building async lifecycle](../reports/2026-11-13-rendering-and-building-async-lifecycle.md).

Implemented 2026-11-12 for 42.22.2-client after the
[schema 4 capture](../reports/2026-11-12-schema-4-capture-analysis.md).
Extends the [worker/probe guide](2026-11-12-texture-workers-and-client-probes.md)
and [main telemetry guide](2026-11-12-client-main-thread-telemetry.md).
No installed game files or JVM heap settings were changed. Gameplay validation
and representative enabled/disabled captures remain necessary.

## Deferred texture preprocessing

Eligible raw-image tasks use a dedicated two-thread daemon executor with a
16-entry queue. FileSystemImpl retains its existing 16-task in-progress limit.
On queue rejection the task is returned to the existing pending list and retried
later: it is neither lost nor executed on the main thread. The upstream vanilla
asset-request lists are unchanged; the new executor queue is bounded.

Queue entries contain requests, not decoded images. Decode starts on a worker,
using the original decoder and its original native-buffer throttle. Cancelled
queued futures are purged before admission. At most two tasks execute there;
completed results still integrate through the original owner-thread callback.

CPU preprocessing admission now waits on fair slot/memory semaphores rather
than returning an eligible decoded image for main-thread resizing. Waiters check
cancellation every 25 ms; cancellation while native decoding or pixel processing
is already running still revokes adoption and disposes the eventual owned result.
Workers never wait for a main-thread preprocessing RPC.

`apocbr.clientTextures.preprocessMiB` now defaults to **256 MiB**, clamped to
1–16384 MiB. Estimates include every extra mip level and the selected output copy.
An image exceeding this budget acquires **all** memory permits and processes
alone in the preprocessing stage. The budget is therefore a concurrency target,
not a hard allocation ceiling or a maximum accepted image size. Another worker
can retain a decoded source while waiting. Decode buffers, ready results and
other game allocations are outside the extra-memory estimate. Large images can
still exhaust actual available memory; no finite-memory implementation can
guarantee arbitrary image sizes.

Packed-image tasks and APNG/missing-data preprocessing retain the original path.
Already-small raw images do not need resizing. Disabled worker preprocessing
restores the original raw-image path. Settings changes still trigger re-decode;
GPU upload and asset-state notifications remain on their original owners.

## Asynchronous translucent ordering

Enabled by default; `-Dapocbr.clientTranslucent.async=false` selects the original
ordering path for comparison. A single daemon worker prepares ordering for each
chunk/player/level. It merges the item, cutaway-outline and translucent-object
square lists, deduplicates by identity, and sorts with the original integer key
and stable tie order. Duplicate entries already present in the initial item
list remain preserved, matching vanilla. IsoGridSquare does not override equals.

The worker's IdentityHashMap and sorting scratch list are ThreadLocal and clear
their object references after every job. The owner captures square references
and numeric sort keys only when changed data needs a new job. On later calls it
checks the existing snapshot against current lists/keys in O(n); it does not
rebuild unchanged snapshots. The worker never calls methods or reads fields on
those square/chunk references, nor accesses camera, world, asset or GL state.
ThreadLocal scratch alone would not make live-world access safe.

Results publish through volatile fields. The main thread adopts only an exact
identity/order/key match against its current lists. Schema 5 skipped changed
passes while waiting, causing reported flicker. Schema 6 uses a matching cached
continuity result or builds/caches the current ordering on the owner when a
worker result is not ready. It does not wait for the worker, omit the pass, or
render unchecked old pooled squares. This trades occasional main-thread sorting
for visual continuity; catch-up counters make the cost measurable.
Visibility, cutaway evaluation, render flags and draw submission remain current
and main-thread owned. This offloads ordering preparation, not the entire measured
translucent phase; no saving equal to that entire phase is claimed.

There is at most one outstanding job per cached entry, 64 queued jobs globally,
and 1024 cached entries per renderer helper. On queue saturation the main thread
prepares the current ordering and caches it. Entries can retain a worker snapshot
and a separate continuity snapshot. Entries not touched for two seconds are
evicted; changing the current cell abandons and clears the cache. Late abandoned
jobs cannot insert into a new world's cache. Queue/cache limits bound entry
counts, not bytes: each snapshot scales with its chunk-level candidate lists.
Worker RuntimeExceptions increment FAILED and permit a later retry.

## Building reconciliation algorithms

Reconciliation remains synchronous and retains its operation order and live-state
ownership. No partially rebuilt meta grid is exposed between frames.

- Connected-region traversal replaces repeated result-list membership scans with
  a HashSet. An iterator stack preserves vanilla depth-first visitation order and
  avoids recursion-stack overflow on large connected graphs.
- `allWorldRegions` becomes an insertion-ordered LinkedHashSet. The existing done
  set already makes initial insertions unique; subsequent membership/removal now
  avoids linear scans while preserving iteration order.
- Dirty chunks use a HashSet of collision-free packed signed x/y coordinates,
  replacing repeated Location allocation/list searches, including per-loaded-chunk
  membership checks. Existing chunk-coordinate calculation remains unchanged.

Other list searches, including bounded free-square lists and externally mutable
room/light/global-building lists, are unchanged. There is no persistent mirror
index that could become stale after external list mutation. The new whole-class
baseline is local commit `bb7f580`; no push was performed.

## Schema 5

Schema 6 retains these fields and adds OWNER_CATCHUP, OWNER_CATCHUP_NANOS and
CONTINUITY_REUSED to translucentWorker. PENDING and QUEUE_FULL now describe
worker scheduling conditions; drawing continues. See the linked flicker issue.

Existing phase/counter meanings remain. New worker fields are numeric writer
samples, not exact frame attribution:

- workerTextures: QUEUED (successful executor submissions), OVERSIZED (jobs whose
  estimate exceeds budget), CAPACITY_WAIT_NANOS (successful admission wait time),
  UNSUPPORTED (APNG/missing data), plus existing counters. FALLBACK no longer means
  busy or over-budget for eligible static images. Cancelled/failed waits are not
  included in successful admission wait time.
- translucentWorker: enabled, SUBMITTED, COMPLETED, REUSED, PENDING, QUEUE_FULL,
  FAILED, WORK_NANOS. REUSED counts matching prepared results consumed by the owner;
  PENDING counts changed owner requests coalesced behind an unfinished job.
  In schema 5 an initial submission skipped drawing; schema 6 supplies a current
  continuity result instead.
- CELL_TRANSLUCENT_ORDER measures owner-side validation/capture/admission.
- REGION_BUILD_REMOVE, CREATE, FILTER, COMBINE, INSERT and SQUARES separate
  reconciliation work. These nest inside REGION_PROCESS_BUILDINGS; cleanup/debug
  work and some invalidation remain outside the new children. Do not add nested
  parent and child totals.

## Validation

Final verification: all 10 client tests pass (72.259 seconds). Target DryRun
compiles 19 sources / 62 classes. javap public APIs match the original game JAR
for WorldRegionToMetaGrid, FBORenderCell and FileSystemImpl. All client document
links and index coverage pass. Disposable install/revert covers both root and
java-subdirectory layouts; the actual installed client was not modified.

Production-helper tests compare stable translucent ordering with duplicates,
identity-only worker access, changed coordinates/lists, player/world isolation,
queue saturation and cache bounds. Extracted original/patched building methods
compare traversal order on random cyclic graphs, dirty-chunk lookups including
negative coordinates, insertion/removal order and a 100,000-region chain.

Native ImageData fixtures compare vanilla pixels/mipmap debug colors, resize
above a deliberately tiny budget, fill the real bounded texture executor, purge
cancelled queue entries, cancel capacity waiters and exercise ownership races.
The file-system fixture covers rejection/re-admission and owner completion order
using an external executor double. Exact-JAR compilation and disposable install/
revert fixtures cover class compatibility and packaging; these do not establish
live rendering equivalence, absence of visual omissions, or measured speedups.

Reinstall using `instalador.bat` with the client closed, capture a fresh telemetry
file, and compare the same route with async ordering enabled/disabled. Check
aiming, cutaways, split-screen, streaming/unload, reconnect and settings changes.
