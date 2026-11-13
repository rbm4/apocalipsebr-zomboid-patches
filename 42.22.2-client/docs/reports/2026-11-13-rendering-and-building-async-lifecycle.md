# Translucent rendering and practical building-reconciliation detachment

Implemented follow-up: [worker geometry and bulk owner commit](../features/2026-11-13-async-building-reconciliation.md).
The implementation keeps authored-overlap filtering and level combination on the
owner; the extracted worker performs graph collection and initial room geometry.

Source investigation 2026-11-13, exact 42.22.2-client. Scope is rendering,
tiles/objects and region-derived buildings; packet work and bird callbacks are
excluded from proposed changes. Measurement reference: [schema 7 capture](2026-11-13-schema-7-capture-analysis.md).
Owning [render/worker guide](../features/2026-11-12-client-deferred-textures-and-async-translucency.md)
and [main telemetry guide](../features/2026-11-12-client-main-thread-telemetry.md).

This document is a code-backed implementation assessment. No runtime behavior,
installed game files, packet handling or Lua mod was changed.

## What the translucent worker actually removes

The 2.992 ms/frame CELL_TRANSLUCENT_OBJECTS measurement includes the complete
renderTranslucentObjects pass. Its nested ordering helper is 0.1506 ms/frame;
approximately **2.8414 ms/frame remains outside ordering**. These are main-owner
wall timings, not GPU durations. No individual subcall measurement yet divides
that remainder exactly.

FBORenderCell.renderOneLevel_Translucent obtains a prepared square ordering,
then performs the following live work for each candidate square:

1. Checks z, levelData.shouldRenderSquare and square.IsOnScreen.
2. renderTranslucent(square) scans square.getObjects, reads current per-player
   ObjectRenderInfo.layer and renders matching Translucent objects.
3. Optionally renders world inventory objects. That copies/sorts item references
   and evaluates their rendering conditions.
4. renderTranslucentSE(square) scans the **same object list again**, selecting
   TranslucentSE objects.
5. renderCutawayOutline(square,false) scans that list a **third time**, selecting
   current cutawayOutline flags and rendering those objects.

The enclosing pass also iterates candidate chunks per level, handles depth state,
allocates/initializes tree draw batches and submits them. renderTranslucent(object)
calculates door/wall/window/fascia/roof target alpha using current camera/player,
adjacent squares, cutaway flags and lighting. It writes targetAlpha and may
dispatch floor, door/wall, other-object or animated-attachment rendering. Tree
batches flush when required by object ordering. These operations remain on the
main owner, even with async ordering enabled.

The objects need not be physically moving for this work to change: their
renderInfo.layer, cutawayOutline and targetAlpha are driven by player/camera/
cutaway state. Persistently caching them based only on object-list membership
would miss those changes. Running draw methods on the ordering worker would
also introduce shared renderer/state mutation.

### Practical rendering change

Separate timers and counts for square filtering, ordinary translucent object
scan/draw, item work, southeast scan/draw, outlines, alpha calculation and tree
flushes. Also instrument the 3.180 ms/frame residual inside performRenderTiles:
shadows, visibility polygon, water/puddles, player/shadow work, attachments,
markers/highlights/outlines and remaining manager work.

A concrete algorithm candidate is to build ordered per-square/per-player object
groups during the existing render-info classification or one current-frame scan,
instead of re-filtering every object's list three times. Execute those groups
in the original order: translucent, items, southeast, outline. Preserve tree
flush boundaries and any render-call effects. Tie validity to current render-info
and cutaway invalidations, object addition/removal/reuse and player index, not
only chunk content. This could reduce traversal/classification but does not
prove how much of the 2.84 ms belongs to those scans rather than actual drawing.
Current continuity catch-up must remain available; a missing async result should
not again make the whole pass disappear.

## What building reconciliation is for

WorldRegionToMetaGrid is the bridge from computed region connectivity to the
client's room/building metadata. It recognizes enclosed world regions with at
least 50% roof coverage, traverses qualifying connected regions, converts region
square membership into RoomDef rectangles, groups rooms into BuildingDef values,
rejects candidates adjacent to/overlapping authored buildings, combines qualifying
levels and installs the resulting user-defined buildings into the meta grid.
It does not simply rebake rendering textures or regenerate all authored buildings.

For each changed region cell it also considers eight neighboring cells. A region
cell covers 32x32 chunks, each 8x8 squares: 256x256 world squares. Even a small
local change can trigger work across a much larger metadata neighborhood.
Connectivity and merged buildings can extend across cell boundaries.

## Current call/lifetime boundary

```text
IsoRegionWorker.thread_run_batched_jobs, cycle 0
  update private rootBuffer -> processDirtyChunks
  request buffer swap -> wait for main acknowledgment
IsoRegions.update, main owner
  swap current DataRoot with worker rootBuffer
  acknowledge swap
  reset cached square region references
  DataRoot.clientProcessBuildings
    static WorldRegionToMetaGrid.clientProcessBuildings(changedCells)
  clear changedCells
IsoRegionWorker, cycle 1
  replay changes into the other root, then continue queued work
```

The acknowledgment currently precedes building processing. It does not pin the
old or new graph for a later independently queued building job. DataRoot owns an
IsoRegionManager that pools world/chunk regions and recycles IDs; raw references
or IDs alone are not stable asynchronous handles. Also DataRoot.wrtmg is static,
so it is not an independent reconciler owned by each root/worker.

Current reconciliation is destructive: remove old user-defined rooms/buildings,
clear old region bindings, release definition objects to pools, build replacements,
insert/reindex, then refresh squares and invalidate consumers. It is coherent to
normal main-thread callers because those callers do not run midway through the
method. Simply enqueueing it after the root swap breaks that assumption.

## Consumers that must move together

| Published state | Concrete dependency |
| --- | --- |
| Meta-cell rooms/buildings and IDs | IsoMetaGrid.getRoomDefByID/getRoomByID; IDs encode list indices |
| Square room cache | IsoGridSquare.setRoomID resolves/cache room; getRoom returns that cached object, not a fresh lookup each frame |
| Room/building runtime objects | Lazy getRoomByID builds IsoRoom/IsoBuilding and adds them to world caches/lists |
| associatedBuilding/roofHideBuilding | FBORenderCutaways uses these for collapse, roof visibility and room/roof-room selection |
| Interior/exterior properties | setRoomID unsets exterior; RecalcProperties derives exterior from room ID/roof state and invalidates water/puddles data |
| Room lights/switches | updateSquares creates room lights, registers them in currentCell.roomLights and associates light switches |
| Generator toxicity | updateSquares and IsoGenerator set building toxic state; character update reads current-building toxicity |
| Lighting/LOS | LightingJNI.buildingsChanged schedules building resends, clears LOS caches and marks global lights dirty |
| Path/visibility/audio invalidation | PolygonalMap2.squareChanged forwards native/path updates and invalidates visibility polygons, cutaways and ambient walls |

IsoRoom.clear(false) nulls its def/building, clears lists and removes native room
lights. Existing squares can still point at that IsoRoom until setRoomID refreshes
them. Pool release may reuse the underlying RoomDef/BuildingDef for another
building. These are not equivalent to a harmless temporary missing render result.

Allowing the **previous complete generation** to stay visible while replacement
calculation runs is a practical delayed-update policy. Removing/clearing that
generation first and hoping later frames heal it is not: the getters cache room
objects, and the required refresh currently happens explicitly in updateSquares.
No ordinary render loop reruns clientProcessBuildings to repair it automatically.

## Concrete algorithm work still present

The earlier set optimizations did not cover these lower-level paths:

1. DataRoot.getIsoWorldRegionsInCell (line 91) scans every chunk/level and uses
   `worldRegions.contains(worldRegion)` for deduplication. It is called by **both**
   removeUserDefinedBuildingsFromCell and createBuildingsFromRegions. Replace this
   with method-local set membership while preserving first-encounter output order
   and existing null/equality behavior. A batch-local result cache can avoid
   collecting the same cell twice against an unchanged root.
2. Changed-cell plus eight-neighbor loops revisit overlapping cells repeatedly.
   Build a first-encounter ordered unique coordinate set once per reconciliation.
   This reduces repeated collection/scanning without a persistent stale index.
3. removeFromMetaGrid and addToMetaGrid each invoke
   recalculateBuildingAndRoomIDs. That clears/rebuilds all room IDs, building IDs,
   isoRooms and isoBuildings for that meta cell **after each individual building**.
   Rebuilding once per affected meta cell is a concrete batching opportunity.
   It requires a bulk commit path, not deleting the calls blindly: old IDs are
   used by removal and new IDs are assigned from room-list positions. Spatial
   indexes, cached runtime objects and square IDs must remain consistent.
4. BuildingDef.CalculateBounds repeatedly calls linear overlapsChunk while
   collecting chunk coordinates from rectangles. A temporary membership set can
   preserve first-encounter order with faster duplicate checks. Preserve current
   division/boundary/short-coordinate semantics, including negative coordinates;
   this needs differential tests rather than a naive arithmetic rewrite.

These are verified call patterns, not a measured division of the 84–115 ms
removal and 132–134 ms creation events. Add collection/bounds/reindex counters
and subscopes while changing them so the next capture verifies which mattered.
Authored-building overlap/level combination also scan geometry but were smaller
than removal/creation in this capture.

## Recommended async lifecycle

Reuse the existing region worker as the planning owner, before it requests the
cycle-0 swap. It already owns the newly computed region graph at that point.
This avoids copying a large graph out of the main thread or racing a new worker
against graph reuse.

```text
OLD_PUBLISHED: main uses previous coherent region/building state
WORKER_PLAN: private root -> value-only replacement geometry
READY: immutable root-generation + building plan pair
MAIN_COMMIT: validate, install coherent metadata, refresh affected consumers
RETIRE/ACK: release retired definitions/root ownership; worker proceeds
```

### Planner inputs and output

- Worker-owned region graph/changed-cell footprint, enclosure/roof flags, adjacency
  and square membership. Capture traversal/order semantics exactly. Existing
  BuildingDef references on the private root are not owned geometry snapshots;
  derive topology without dereferencing stale published/pooled definitions.
- Immutable authored-building geometry needed by the overlap filter, published
  from its owner with a geometry revision. The worker must not query/mutate the
  live meta grid while the main thread changes it. Use a prebuilt immutable index
  or scoped snapshots, not per-frame copies of all buildings.
- Output detached room rectangles, bounds, grouped levels, affected meta-cell/
  chunk footprints and region keys scoped to the exact root generation. No live
  square, runtime room, lighting or renderer access in the planner. Do not call
  the existing destructive reconciler on the worker.

The mathematical region traversal, rectangle extraction, geometry bounds,
authored-overlap tests and level combination can be implemented against these
inputs. Build only value geometry on the worker; materialize/publish definitions
under the owner contract. If audited private definition construction is later
allowed, it must still avoid published globals and premature pool reuse.

### Main commit

- Validate world/session, root generation and relevant authored-geometry revisions.
  New movement alone should not invalidate all plans; later region jobs should
  catch up naturally, preserving coherent generation order.
- Keep old metadata intact while preparing the replacement. Pin both root and
  retired-definition lifetime through publication and cache refresh. The old
  swap acknowledgment is not a retirement fence; introduce an explicit barrier.
- Apply removals/additions through a bulk path, finalize IDs/maps once per affected
  meta cell, preserve authored buildings and refresh dependent square/runtime
  references before pooling old definitions. Audit global building registry and
  adjacent-cell spatial indexes as part of this commit.
- Run the existing lighting, square-property, path/visibility/audio and render
  invalidations on their owner, then acknowledge/retire and continue region work.
- Bound outstanding plans and retained input/output memory; coalesce later dirty
  work without losing edits. Reconnect/reset discards stale session results.
- Replace DataRoot.clientProcessBuildings' current changedCells consumption
  explicitly: bind consumed coordinates to the accepted root/plan generation.
  Rejected/cancelled work must be requeued or retained, not silently cleared.
  Plan once before the cycle-0 swap; cycle-1 graph replay must not repeat the
  expensive published-building work.

This intentionally allows older room/roof/interior metadata while planning, which
is consistent with the user's requested delayed correction tradeoff. It does
not permit partially removed definitions to be visible between frames.

### Can commit itself be round-robin?

Only across independently consistent dependency groups. One building/connected
region may span cells and combined levels; one cell update can renumber IDs used
by many rooms. A fixed count of rooms or arbitrary cells is not automatically
a safe unit. Use the affected building/region closure plus all touched owner/
adjacent spatial indexes and ID/cache dependencies. During private preparation,
work can be freely sliced; publication must expose a consistent completed group.

The measured removal phase is large, so async geometry alone will not guarantee
the whole hitch disappears. Optimize/batch the commit and measure it first. If a
single coherent group still takes too long, a deeper versioned metadata/index
publication design is needed; merely delaying the same 100 ms removal would move
the hitch to a later frame.

## Practical implementation order

1. Complete render subpass probes and add reconciliation collection/bounds/reindex
   timings/counts. Apply unique-cell enumeration and set-based region/chunk
   deduplication with differential output-order tests.
2. Build a bulk owner commit that preserves old state until replacement is ready,
   rebuilds IDs once per affected cell and refreshes cached references correctly.
3. Extract the value-only geometry planner and run it on the existing region
   worker before swap; publish generation-bound root/plan results with retirement.
4. Consider budgeted independent-group commits only if remaining measured commit
   time needs it. Test door/wall/roof edits, adjacent/multilevel/cross-cell rooms,
   author-building overlap, light switches/generators, streaming, split-screen,
   reconnect and root/pool reuse. Aiming/movement tests must also check cutaways.

Expected change locations: WorldRegionToMetaGrid, DataRoot, IsoRegionWorker,
IsoRegions, and a new value-only building planner; BuildingDef if its shared
bounds helper is optimized. Rendering work belongs in FBORenderCell and, if
cached object groups are added, FBORenderLevels with its existing invalidation
lifecycle. Exact-version whole-class compatibility and original-JAR preservation
remain required. No timing savings or gameplay correctness are claimed before
implementation and validation.
