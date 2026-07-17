# Load-Finalize Cell-Boundary Race - Fix Scoping (Build 42.19.0)

## Status
OPEN

## Priority
HIGH (root cause candidate for "building interior stays permanently black, does not un-hide even after breaking a wall, and reproduces for later players at the same spot")

## Relationship to existing reports

This is a **distinct mechanism** from `PATCHED-SOURCE-DIFF-AUDIT-2026-07.md` Finding 1
(unload path no longer suspending `ServerLOS` around `processDeferredUnloads()`). Finding 1
is an **unload-side** race. This report covers a **load-side** race in the cell finalize
(`Load2()` / `RecalcAll2()`) path. Both produce the same symptom family and are not mutually
exclusive - a real server can hit either or both. This report only scopes the load-side fix.

Do not treat implementing one as a substitute for the other.

## Confirmed root cause chain

### 1. The patch turned an atomic same-tick finalize into a budgeted, multi-tick one

Vanilla `ServerCell.Load2()` (`decompiled/zombie/network/ServerMap.java:526-531`) drains
**every** cell currently in `loaded2` unconditionally, every tick, with no time cap:

```java
for (int x = 0; x < ServerMap.ServerCell.loaded2.size(); x++) {
    ServerMap.ServerCell cell = loaded2.get(x);
    if (cell.Load2()) {
        x--;
        this.toLoad.remove(cell);
    }
}
```

The patched version (`src/zombie/network/ServerMap.java:585-667`) sorts `loaded2` by distance,
applies a starvation guard (`FINALIZE_MAX_WAIT_MS = 1500L`, moves the oldest-waiting cell to the
front), then drains it under a **time budget** (`budgetNanos`, from `getFinalizeBudgetNanos()`,
`src/zombie/network/ServerMap.java:759-765`) that scales down as frame time gets worse:

```java
while (!ServerMap.ServerCell.loaded2.isEmpty()) {
    if (finalized > 0 && System.nanoTime() - finalizeStart >= budgetNanos) {
        break;
    }
    ServerMap.ServerCell cell = ServerMap.ServerCell.loaded2.remove(0);
    ...
    if (cell.Load2()) { ... }
}
```

Under load (many cells streaming in from multiple players, or frame time already elevated so
`budgetNanos` shrinks), cells that became "ready" in the same batch now get their `RecalcAll2()`
(the actual finalize work) spread across **different ticks** instead of always the same one.
This is intentional and correct for avoiding hitches - the problem is what a cell's finalize
sees when a *neighbor* hasn't been drained yet.

### 2. `isLoaded` is an all-or-nothing per-cell gate that blocks cross-boundary reconciliation

`ServerMap.getGridSquare()` returns `null` for any square belonging to a cell whose `isLoaded`
is still `false` (`decompiled/zombie/network/ServerMap.java:949-956`, unchanged by the patch):

```java
ServerMap.ServerCell cell = this.getCell(cx, cy);
if (cell != null && cell.isLoaded) {
    IsoChunk c = cell.getChunk(chx, chy);
    return c == null ? null : c.getGridSquare(sqx, sqy, z);
} else {
    return null;
}
```

`isLoaded` only flips to `true` **inside** `RecalcAll2()` (`src/zombie/network/ServerMap.java:1167`
and again at `:1297`), i.e. only once finalize has actually run for that cell. Chunk *data* can
already be fully loaded into `cell.chunks[][]` (by `ServerChunkLoader.LoaderThread`) well before
that, while the cell still sits in `loaded2` waiting for its budget turn.

`RecalcAll2()`'s own boundary-reconciliation pass
(`src/zombie/network/ServerMap.java:1227-1251`, structurally identical to vanilla
`decompiled/zombie/network/ServerMap.java:937-960`) walks the cell's 4 edges and calls
`sq.RecalcAllWithNeighbours(true)`. If the neighboring cell hasn't finalized yet,
`getGridSquare` returns `null` for every square across that edge, so
`IsoGridSquare.RecalcAllWithNeighbours` (`decompiled/zombie/iso/IsoGridSquare.java:5380-5384`)
silently **skips** establishing vision-blocked / pathfind / collide linkage across that shared
edge, for both squares (the skip happens before `ReCalculateAll`, so neither side gets updated).

The background per-cell `ServerChunkLoader.RecalcAllThread` cannot compensate: its
`GetSquare.getGridSquare()` (`decompiled/zombie/network/ServerChunkLoader.java:112-124`) is
hard-bounded to the *owning* cell's own 64x64 span (`x -= cell.wx*64; if (x < 0 || x >= 64)
return null;`). It never reaches into a neighboring cell's data by design, even if that data is
already sitting in memory.

This gap is normally self-healing: when the neighbor cell later finalizes, its own boundary pass
reaches back with `bDoReverse=true` (`decompiled/zombie/iso/IsoGridSquare.java:5314-5343`,
`ReCalculateAll(true, a, getter)` updates **both** `this` and `a`), fixing the link from the
other direction. The bug is not that the gap exists (it can exist in vanilla too, briefly) - it's
that the patch's budget makes the gap **wide enough**, **under exactly the load conditions that
make it more likely to be sampled** (see step 3), for it to actually get observed and latched.

### 3. A single bad sample during the gap gets permanently latched, not just delayed

`IsoGridSquare.CalcVisibility()` (`decompiled/zombie/iso/IsoGridSquare.java:9329-9370`) runs a
`LosUtil.lineClearCached(...)` raycast. If the boundary link is stale at that instant, the ray
reports `Blocked`, the square falls into the `else` branch, and - critically -
`room.def.explored` is only ever set to `true` inside the *non*-blocked branch
(`:9344-9361`). On the server side the `else` branch does nothing further (the ambient-light
math there is gated by `!GameServer.server`), so a `Blocked` sample produces no compensating
state change.

`ServerLOS.LOSThread.calcLOS()` (`decompiled/zombie/network/ServerLOS.java:208-216`, unchanged
by the patch) only re-runs this scan when the player's floored tile changes:

```java
boolean skip = data.px == PZMath.fastfloor(data.player.getX())
    && data.py == PZMath.fastfloor(data.player.getY())
    && data.pz == PZMath.fastfloor(data.player.getZ());
...
if (!skip) { /* full 96x96xZ CalcVisibility/checkRoomSeen scan */ }
```

Once the player stops moving tile-to-tile (e.g. right at the wall they're breaking down), no
further `CalcVisibility`/`checkRoomSeen` call happens for them - whatever `explored` /
`isCouldSee` state was computed during the one bad sample is frozen indefinitely.

### Why this specifically matches every reported symptom

- **Only specific buildings**: only rooms whose footprint straddles a `ServerCell` (64x64 tile)
  boundary are exposed to this gap at all. Buildings fully inside one cell never hit it.
- **Walking reproduces it, a one-off teleport didn't**: walking guarantees many tile-changing
  LOS recomputes while the destination cells are actively streaming in, so it is likely to sample
  `CalcVisibility` during the finalize gap. A single teleport is one sample that can simply miss
  the (usually short) window, especially if the destination cells were already relevant/warm from
  prior activity. This is a probabilistic race, not a deterministic per-location property - a
  teleport landing cleanly is not proof the location is unaffected.
- **Breaking the wall in place does not fix it**: the player's tile does not change when breaking
  an adjacent wall, so `ServerLOS` never re-samples them; the stale result is never revisited.
- **A second, later player reproduces it at the same spot**: if the latched state is
  `RoomDef.explored = false` (a shared object keyed by room, not per-player), any player whose
  LOS sample also lands on that square while stationary nearby reproduces the same frozen
  darkness - no persisted/disk corruption required, matching your teleport test.

## Fix Alternatives

### Option 1 (recommended, primary): Boundary-aware getter for the finalize reconciliation pass

Add a `IsoGridSquare.GetSquare` implementation used **only** by `RecalcAll2()`'s boundary loop
(`src/zombie/network/ServerMap.java:1227-1251`) that, like
`ServerChunkLoader.GetSquare`, reads a neighboring cell's `chunks[][]` array directly - keyed off
the neighbor `ServerMap.ServerCell` object and its chunk data, not its `isLoaded` flag. Concretely:
resolve the neighbor `ServerCell` via `ServerMap.instance.getCell(cx, cy)` (bypassing the
`isLoaded` check that `getGridSquare()` applies), then read `cell.chunks[chx][chy].getGridSquare(...)`
if that chunk slot is non-null.

This is safe because:
- All of this happens on the main thread (`ServerMap.update()` calling `Load2()`), same thread
  that populates `chunks[][]` reads/writes for finalize purposes.
- If the neighbor cell hasn't even started loading, `chunks[][]` is fully `null` there and the new
  getter naturally returns `null`, same as today - no behavior change for the "genuinely not
  loading" case, only for "data present, finalize not yet run".

This directly removes the root cause: a cell's boundary reconciliation no longer treats a
neighbor with in-memory-but-unfinalized data as nonexistent.

**Complexity:** moderate. New getter class (mirror `ServerChunkLoader.GetSquare`, adjusted to
resolve `cell` per-coordinate instead of a single fixed cell since we're now looking both at
`this` cell and its neighbor). Swap the getter used in the 4 edge-loops of `RecalcAll2()` from the
default `ServerMap.instance.getGridSquare` path to the new getter's `getGridSquare`.

**Risk:** low-medium. Must confirm `IsoChunk.getGridSquare()` on a chunk whose own `RecalcAll2`
hasn't run yet still returns a structurally valid `IsoGridSquare` (walls/room id/objects present -
they are, since that data comes from `ServerChunkLoader.LoaderThread`'s disk load +
`RecalcAllThread`'s per-cell-local pass, both of which complete before a cell enters `loaded2`).
Only the *cross-boundary* linkage is missing, which is exactly what this getter lets
`RecalcAllWithNeighbours` fill in early.

### Option 2 (recommended, complementary safety net): Invalidate the LOS skip-cache near cells that just finalized

Even with Option 1, do not assume every possible race is eliminated (e.g. a player whose LOS
sample lands in the single tick between "chunk data present" and "finalize turn taken" even with
a smarter getter, if a chunk is present in `chunks[][]` for the neighbor cell mid-populate rather
than atomically). As a cheap, independent safety net: when a `ServerCell.Load2()` finalize
completes, find any `ServerLOS.PlayerData` for players within LOS range of that cell (or, simpler,
players inside or adjacent to the cell that just finalized) and force their next `calcLOS()` call
to do a full recompute regardless of tile position - e.g. reset `data.px`/`data.py`/`data.pz` to a
sentinel value (`Integer.MIN_VALUE`) so the `skip` check in
`ServerLOS.LOSThread.calcLOS()` (`decompiled/zombie/network/ServerLOS.java:208-216`) evaluates
`false` on their next tick even if their real tile hasn't changed.

This does not fix the geometry race itself, but it guarantees that any latched bad `explored`/
`isCouldSee` state gets one more chance to self-correct shortly after the boundary is actually
reconciled, without requiring the player to move. This is the fix most directly aimed at "persists
even after breaking the wall / persists for a stationary player."

**Complexity:** low. Needs a way for `ServerMap`/`ServerCell` to signal `ServerLOS` (already have
a coupling point via `ServerLOS.instance.suspend()`/`resume()` calls in the same load-finalize
path) - e.g. a small method like `ServerLOS.instance.invalidateNear(cell)` called right after
`cell.Load2()` succeeds in `src/zombie/network/ServerMap.java` (near line 643).

**Risk:** low. Worst case is a few extra full LOS scans right after cells finalize, which is
already a moment of elevated load-finalize activity, so the added cost is small and localized.

### Option 3 (alternative, not recommended as primary): Force same-tick co-finalization of contiguous ready batches

Instead of finalizing individual cells under a flat time budget, group cells in `loaded2` into
connected components (by adjacency) and only finalize a component once every cell in it is ready,
finalizing the whole component atomically in one tick.

**Rejected as primary** because it can blow the frame budget under exactly the load-pressure
scenario that causes the bug (large contiguous streaming batches - many players approaching a big
custom-map building from different directions), which is the failure mode the budget mechanism
was added to prevent in the first place. Could be considered as a secondary refinement layered on
top of Option 1 if telemetry after Option 1 still shows meaningful gap windows, but do not start
here.

### Option 4 (rejected): Revert to vanilla's unconditional full-drain `Load2()`

Removes the race by removing the multi-tick budget entirely. Explicitly out of scope per the
existing constraint: the budget exists to avoid frame hitches from large finalize batches, which
is the same problem the multi-tick *unload* queue solves on the other side. Reverting this
reintroduces the original hitching the patch was written to fix.

## Diagnostics to add before implementing (to empirically confirm the gap, not just the theory)

Add temporarily (behind `DebugType.MapLoading` or a new `ApocBRServerTelemetry` counter):

1. In `RecalcAll2()`'s boundary loops (`src/zombie/network/ServerMap.java:1227-1251`), log/count
   when `ServerMap.instance.getGridSquare(...)` returns `null` for a boundary coordinate that
   *does* have a non-null neighbor `ServerCell` object with a non-null `chunks[][]` slot (i.e.
   "data present, but isLoaded=false" - the exact gap condition). A telemetry counter is enough;
   full logging will be extremely noisy under normal streaming.
2. Log the wall-clock delta between when two immediately-adjacent cells each call `RecalcAll2()`
   (a small `IdentityHashMap<ServerCell, Long>` populated at the top of `RecalcAll2()` is enough,
   diffed against neighbors' timestamps). Correlate large deltas with player reports.
3. Log every `room.def.explored` transition (`false -> true`) with the `LosUtil.TestResults` value
   that gated it, and every `CalcVisibility` sample that hit the `Blocked` branch for a square
   whose room is still unexplored, tagged with whether that square is on a cell boundary edge.

Reproduce by: have 2+ players approach a known custom-map building that straddles a cell boundary
from different cells simultaneously, under artificial load (e.g. temporarily lower
`getFinalizeBudgetNanos()`'s thresholds, or add other streaming load) to widen the gap
deliberately, then confirm the telemetry counter from (1) is nonzero exactly when the visual bug
reproduces.

## Suggested implementation order

1. Land the diagnostics above, reproduce on a test server, confirm counter (1) correlates with the
   reported black-building sightings before changing any load-path logic.
2. Implement Option 1 (boundary-aware getter). Re-run the same reproduction; counter (1) should
   drop to zero for the "data present but not finalized" case specifically (a `null` because the
   neighbor genuinely hasn't started loading yet is still expected and fine).
3. Implement Option 2 (LOS skip-cache invalidation near finalize) as a safety net regardless of
   whether (2) alone appears sufficient in testing - it is cheap and covers residual/unknown edge
   cases, including interaction with Finding 1 from `PATCHED-SOURCE-DIFF-AUDIT-2026-07.md`.
4. Regression-test: verify `RecalcAll2()` finalize time per cell does not regress meaningfully
   (Option 1 adds at most a few extra cell/chunk lookups on the 4 edges, not a new scan), and that
   `ApocBRServerTelemetry.recordServerMapLoadFinalize` metrics stay within prior norms.
5. Soak-test with multiple players streaming into the same custom map area simultaneously
   (the original reported condition) before shipping.

## Test / validation plan

- **Repro case:** custom map building known to straddle a `ServerCell` boundary (use the telemetry
  from diagnostics step to find one, or compute directly from the building's world coordinates
  modulo 64). Have a player walk to it from outside render distance while server is under
  artificial multi-cell load. Confirm interior lights correctly and stays lit while stationary and
  after breaking a wall.
- **Regression case:** same building, single idle player, no load - confirm behavior and finalize
  timings are unchanged from current production.
- **Multiplayer case:** two players approach the same boundary building from opposite cells at the
  same time - this is the scenario most likely to still show a residual gap if Option 1 has an
  off-by-one in edge coordinates; confirm both players see it lit correctly.
- Keep the diagnostics counter from step 1 in place (behind a debug flag) for at least one
  production cycle after shipping the fix, to catch any residual/rare gap instead of relying on
  player reports alone.
