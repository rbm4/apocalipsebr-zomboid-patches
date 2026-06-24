# ServerCell load commit: main-thread analysis

## Scope and evidence

This report covers only the interval after `ServerChunkLoader.RecalcAllThread` has
already returned a `ServerMap.ServerCell` to `loaded2`. It does not propose a
behavioural change. The new `serverMapLoadCommit{...}` telemetry identifies the
time of every remaining main-thread phase.

The production snapshot that motivated the investigation finalized 48 cells at
87 ms average (219 ms maximum). This was about 20 ms per server tick. A separate
47-cell unload stream added about 22 ms per tick and is intentionally excluded
from the load-commit analysis.

## Actual pipeline

1. `LoaderThread` reads/builds the 8x8 chunks for a 64x64-tile ServerCell.
2. `RecalcAllThread` already works on that cell off-thread. It creates missing
   ground squares, recalculates square properties, adds squares to rooms,
   recalculates internal neighbours, and derives roofs/exterior state.
3. The main thread drains the worker result into `loaded2`.
4. `ServerCell.finalizeLoad()` calls `RecalcAll2()` and `loadVehicles()`.
5. The result becomes visible to normal world update, LOS, collision, pathfinding,
   zombie population, vehicle persistence, and network relevance.

The worker phase is not a pure immutable build: it already mutates the cell's
chunks and `IsoWorld.instance.currentCell`. It remains safe only because the
cell is not published as loaded while that worker owns it.

## Main-thread commit phases

| Phase | Main-thread mutations and dependencies | Classification |
|---|---|---|
| `publish` | adjusts `RoomDef.indoorZombies`, clears cell rooms, sets `isLoaded`, derives levels | Atomic publish; room population is global |
| `borderSurround` | reads adjacent published cells; calls `EnsureSurroundNotNull` | Shared-grid mutation; resumable only with a cell fence |
| `borderRecalc` | `RecalcAllWithNeighbours(true)` on all four borders | Shared-grid/collision/property mutation; resumable with ordering |
| `chunkFlags` | marks chunks loaded, tracks unexplored rooms, marks properties dirty | Cell-local writes plus shared room definitions; resumable after publish fence |
| `gridLoad` | `IsoChunk.doLoadGridsquare()` for each of 64 chunks | Potential entity/world-list attachment; must be audited before worker use |
| `indoorZombies` | increments room population and calls `VirtualZombieManager.tryAddIndoorZombies` | Global zombie/entity mutation; atomic main-thread phase |
| `vehicles` | `VehiclesDB2.loadChunkMain` for every non-new chunk | Explicitly main-thread API; atomic main-thread phase |

`finalizeLoad()` also runs under a suspended `ServerLOS`, but that is not a
general world write lock. It does not fence cell updates, object lists,
population, vehicle DB access, collision, or pathfinding readers.

## Why the existing budget does not cap a slow cell

`finalizeReadyCells()` checks its budget only before beginning an additional
cell. The first `cell.finalizeLoad()` always completes as one atomic operation.
Therefore an 8–20 ms budget prevents batching multiple cells, but cannot prevent
a 87–219 ms single-cell commit.

## Design comparison

### A. Aggressive worker-side commit experiment

Create a per-cell commit plan after worker recalc. The plan carries only the
cell identity, 64 chunks, boundary coordinates, level range, and a generation
token. Do not publish the cell while the worker experiment is active.

The experiment may move only a proven cell-local subset (`chunkFlags` and any
audited portion of `gridLoad`) to the worker. It must never call vehicle loading,
indoor zombie creation, room-global accounting, or border recalc off-thread.

Before worker mutation, install a cell generation token and an unpublished
state. Every worker write verifies that token; cancellation invalidates it. The
main thread checks the same token before publication. On exception, stale token,
or an invariant failure, discard the prepared cell and fall back to the current
main-thread `finalizeLoad()` path.

This can reduce commit time only if telemetry proves that the isolated phases
dominate. It cannot safely make main-thread time zero: global publication,
border repair, indoor zombies, and vehicles remain atomic.

### B. Safe resumable main-thread state machine

Replace one `finalizeLoad()` call with per-cell phases: publish/fence, each
border, chunk-flag batches, chunk-grid batches, indoor zombies, vehicles, then
ready. Each frame consumes work until the existing finalization budget is spent.
The cell remains `FINALIZING` and is excluded from normal updates/relevance until
the final atomic publication stage.

This has lower corruption risk and gives a hard main-thread budget, but delays
cell readiness by multiple frames. It is the preferred production route if
phase telemetry shows border/grid work dominates.

## Required invariants for either future design

- A cell is never visible as loaded before all grids, borders, vehicles and
  indoor population are coherent.
- A cancelled cell never publishes after its generation token changes.
- `cellMap`, `loadedCells`, `loaded2`, chunk `loaded`, rooms, vehicle DB,
  collision/pathfinding and LOS agree on a cell's state.
- No entity may be attached twice or survive in a cancelled/unpublished cell.
- The existing worker I/O/recalc pipeline and save boundary remain unchanged.

## Validation matrix

Run first-generation terrain, saved terrain, multi-level/roofed buildings,
vehicle-heavy cells, rapid driving, repeated enter/leave cancellation, and
concurrent player arrivals. Compare phase telemetry, queue age and chunk
readiness latency. Fail the experiment on any collision, pathfinding, LOS,
vehicle, zombie population, duplicate entity, or stale-cell exception.
