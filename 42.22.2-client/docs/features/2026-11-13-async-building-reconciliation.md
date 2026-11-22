# Worker building geometry and bulk owner publication

Implemented 2026-11-13 for 42.22.2-client, following the
[render/building lifecycle investigation](../reports/2026-11-13-rendering-and-building-async-lifecycle.md).
Extends [main-thread telemetry](2026-11-12-client-main-thread-telemetry.md) to
schema **8**. Packet handlers, bird callbacks and translucent draw behavior are
unchanged by this implementation. Installed game files were not modified.

First runtime capture: [schema 8 analysis](../reports/2026-11-13-schema-8-capture-analysis.md).
Worker planning is active; the largest remaining owner commit is 55.931 ms,
mostly materialization and authored-overlap filtering. Workloads are not matched
to earlier captures, so this is not a controlled speedup measurement.

## Implemented pipeline

```text
IsoRegionWorker, private root, cycle 0
  processDirtyChunks
  collect unique changed-cell + neighboring-cell scope
  compute connected components, room rectangles, bounds and chunk coverage
  publish prepared plan + request root swap
main IsoRegions.update
  swap root -> reset cached region references
  validate plan root/generation/region IDs
  materialize engine definitions; filter authored overlap; combine levels
  remove/insert through bulk path -> reindex each affected meta cell once
  refresh affected squares, lighting, visibility/path/audio and render caches
  retire old definitions -> consume changedCells -> acknowledge swap
IsoRegionWorker
  replay cycle 1 into the other root, then resume queued jobs
```

The worker prepares geometry **before** requesting the swap, so the previous
published root/definitions stay active while planning runs. It never reads the
live meta grid or creates BuildingDef/RoomDef/Lua/lighting/renderer objects.
BuildingDef's constructor itself creates Lua data and consumes global randomness;
those engine objects are materialized on the owner instead.

The planner owns the region worker's private graph. Its read-only plan retains
region references scoped to that exact root/generation and records their IDs.
Those references are not generic cross-thread handles: publication uses the
existing atomic swap flag, and the worker remains parked until the main commit
has consumed them. The acknowledgment now follows square refresh and retirement.
After commit, region lists retained by the reconciler are cleared so old graph
objects are not followed after the next swap/replay.

There is one prepared-plan slot per DataRoot and one active cycle-0 handoff;
no new thread or executor queue is introduced. Plan storage scales with the
affected region footprint and rectangle count, not a fixed byte ceiling. The
existing upstream region-job queue is unchanged. The worker's finished flag is
volatile; planning checks cancellation during scope/graph/chunk/rectangle work,
and the swap wait now also observes shutdown. Stop still joins the existing
worker before discarding its root. Cancelled planning does not consume edits.

The DataRoot consumer checks source identity, generation and region IDs before
committing. resetAllData invalidates its pending plan. The changed-cell list and
plan are cleared only after successful reconciliation. Unexpected RuntimeException
during pure worker planning is logged/counted and permits synchronous owner
preparation against the same root; cancellation during shutdown does not publish
a swap. Native/engine failures during live publication are surfaced as before;
this is a coherent owner-frame batch, not a rollback transaction for arbitrary
exceptions from engine/native calls.

## What remains on the main owner

Authored-building overlap checks and level combination still use vanilla geometry
methods against owner-owned metadata. They are individually timed. This avoids a
second handshake or a potentially large authored-geometry snapshot. The worker
offloads collection, component traversal, greedy room rectangle extraction and
initial bounds/chunk coverage; it does not run the destructive reconciler.

Publication still performs light removal/creation, ID assignment, lazy runtime
room/building creation, square references/properties, generator/light-switch
association, path/visibility/audio notifications and rendering invalidation.
It runs as one main-thread operation. If this remaining commit is still expensive,
the next capture will identify its substeps. No arbitrary round-robin publication
of half a building/cell was introduced.

## Deduplication and geometry

- DataRoot.getIsoWorldRegionsInCell now uses local set membership instead of
  repeated ArrayList.contains, preserving first-encounter list order.
- A first-encounter ordered cell/neighbor union is collected once into each plan.
  Removal reuses those lists instead of scanning the graph again. There is no
  persistent geometry cache to invalidate after subsequent root updates.
- Greedy room rectangles use a 64-bit free-square mask. The algorithm preserves
  vanilla's lowest-free-square selection, horizontal span and vertical extension
  order while avoiding linear free-list removals.
- Planned room/building bounds and overlapped chunk lists are numeric values.
  BuildingDef.CalculateBounds also gets set-based chunk membership for the
  remaining owner-side combination and other callers. Division, ordering and
  short-coordinate behavior are preserved, including out-of-short duplicate
  behavior rather than silently truncating the lookup semantics.

## Bulk commit and cache lifetime

All candidate engine definitions are materialized and filtered before the first
live removal. Old rooms/buildings remain allocated through publication and
loaded-square refresh; they return to pools afterward.

During removal/insertion, final room/building ID rebuilding is deferred. New
rooms do not overwrite retained old-index lookups while the batch is assembling.
At the end, each affected meta cell rebuilds its room map and runtime room/building
lookup keys once. Rooms whose IDs moved mark their chunk footprint dirty, including
retained authored rooms, so loaded squares refresh their cached IDs/references.
Synthetic roof-room ID caches are invalidated because they depend on room count
and building index. Missing replacement roof rooms now clear roofHideBuilding
instead of retaining a stale runtime building.

Retired user-defined buildings are compacted out of the global building registry
before their definitions can be reused by the pool. Authored definitions retain
their identity and order. Regenerated user-defined definitions append in the
prepared deterministic order; old dynamic object identity is not retained across
rebuilds. Adjacent-cell room spatial updates retain the original engine helpers.
Lighting/LOS, cutaways, occlusion and square notifications remain on the owner.

## Configuration

`-Dapocbr.clientBuildings.async=false` disables worker geometry preparation for
comparison. The same deduplicated geometry algorithm and bulk commit then run on
the main owner; this is not a complete return to the original implementation.
Default is true, with worker preparation selected for multiplayer clients.
Single-player/debug reset paths without a prepared plan use synchronous planning.
Server-side deployment is outside this client target; GameServer.server paths
do not select worker building preparation.

## Schema 8 measurements

Existing schema 7 network and texture/translucent fields remain. New main-owner
phases, also present in retained worst frames:

| Phase | Meaning |
| --- | --- |
| REGION_BUILD_COMMIT | Entire bulk reconciliation, including materialization/filter/combine |
| REGION_BUILD_SYNC_PLAN | Owner geometry preparation when no worker plan is present |
| REGION_BUILD_MATERIALIZE | Engine definition construction from completed geometry |
| REGION_BUILD_REMOVE | Old live definitions removed without per-building reindex/pool reuse |
| REGION_BUILD_FILTER / COMBINE | Existing owner overlap and level-merge algorithms |
| REGION_BUILD_GLOBAL_INDEX | Stable removal of retired global registry entries |
| REGION_BUILD_INSERT | New definitions/spatial entries, with final IDs deferred |
| REGION_BUILD_REINDEX | Once-per-meta-cell final ID and runtime-cache rebuild |
| REGION_BUILD_SQUARES | Loaded-square reference/property/light/render refresh |
| REGION_BUILD_RETIRE | Old definitions/rectangles returned to pools |

REGION_PROCESS_BUILDINGS includes sync preparation, if any, plus commit. Parent
and child phases overlap. The older REGION_BUILD_CREATE field is retained for
schema compatibility but the new path uses MATERIALIZE instead; it is no longer
an appropriate direct comparison to the old geometry/create time.

`buildings` window context reports cells, regions, rectangles, removed, added,
reindexedCells and dirtyChunks, summed over successful reconciliations. Cells
include empty neighboring coordinates visited by planning. The same counts are
attached to retained worst frames for workload correlation.

Worker fields use thread-safe writer-sampled adders: workerPlans, workerPlanMs,
workerCollectMs, workerGeometryMs and workerFailures. The first sample may include
pre-entry work and jobs can span windows; these are neither frame-attributed
timings nor measured frame-time savings. Owner counts and worker samples need
not align one for one. An empty changed-cell plan can count as a worker plan
without producing a nonempty commit.

## Source and verification

Changed replacement classes: DataRoot, IsoRegionWorker, IsoRegions,
WorldRegionToMetaGrid, BuildingDef and ApocBRClientTelemetryLite. New planner:
[ApocBRBuildingPlan.java](../../src/zombie/iso/areas/isoregion/metagrid/ApocBRBuildingPlan.java).
The separate client vanilla baselines were verified/copied with the repository
utility before editing: commits 3ccb1bb, c64aba6 and 9e95cb4, no push.
Exact-JAR compilation produces **25 sources / 86 classes**. All original
public/protected signatures/descriptors are preserved; helper methods are additive.
The final client regression run passed all **14 tests** (70.151 seconds).

Production original/patched reconciler bodies and planner are executed against
explicit engine doubles, not a reimplemented reconciler. Coverage includes:

- 60 synthetic maps x three generations, geometry/final IDs, authored overlap,
  multilevel/connected/cross-cell/negative-coordinate regions and empty replacement.
- Once-per-cell reindex, runtime map consistency, global registry retirement,
  cached square refresh before room pooling, and synthetic roof ID invalidation.
- 2,500 greedy-rectangle comparisons with original room conversion and 400
  original/new bounds/chunk-list comparisons, including signed-short boundaries.
- Real preparation/consume and swap-body execution with a checked acknowledgment,
  unchanged published metadata during worker planning, cancelled input retention,
  stale-root/generation/recycled-region rejection and no worker engine construction.
- Schema/count reset, owner-only counters, worker sampling, original surrounding
  call sites, and existing texture/network/render/helper regressions.

The doubles do not execute native lighting, real adjacent-meta-chunk spatial
indexes, full room/building world initialization, or multiplayer streaming. Exact
JAR/API checks and disposable install/revert validate compatibility/packaging;
runtime equivalence and performance still require an actual client capture.

Reinstall through instalador.bat with the client closed and capture a fresh
schema 8 file. Test login, walking across cell boundaries, walls/doors/roof edits,
multilevel and authored-building adjacency, roof cutaways, generators/switches,
streaming/unload, split-screen and reconnect. Compare worker preparation and the
remaining commit substeps under matching workloads before claiming savings.
