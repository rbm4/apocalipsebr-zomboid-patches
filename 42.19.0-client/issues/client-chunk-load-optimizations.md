# Client chunk-load optimizations - build 42.19.0

**Build:** 42.19.0 (Apocalypse BR client patch)  
**Goal:** Reduce frame stutter caused by synchronous chunk loading, per-object finalization, and cascading lighting recalculations.

---

## Quick summary of changes

| Area | File(s) | What changed |
|------|---------|--------------|
| Per-object chunk finishing | `ApocBRClientChunkLoadScheduler.java`, `IsoChunk.java`, `IsoObject.java`, `IsoCell.java` | Heavy `addToWorld()` finishing work is deferred to a per-frame scheduler with a 4ms budget and player-proximity priority. |
| Chunk load pacing | `IsoChunkMap.java` | `updateInternal()` no longer loads a fixed count of chunks per frame; it loads chunks while a 4ms time budget allows. |
| Overlay / item processing distance gate | `LoadGridsquarePerformanceWorkaround.java` | Overlay sprite updates and container filling only run for squares within 24 tiles of a player on the client. |
| Cascading lighting work | `IsoChunk.java`, `LightingJNI.java` | Neighbor chunks are flagged for lighting per referencing player and limited to an overlapping level range; `LightingJNI.update()` processes flagged chunks with a 2ms budget and near-player priority. |

---

## 1. Deferred per-object chunk-load finishing

### Problem
`IsoChunk.doLoadGridsquare()` iterates every square and object in a newly loaded chunk and calls `IsoObject.addToWorld()` synchronously. On the client this triggers:

- container creation and `ItemPickerJava` work,
- overlay sprite updates,
- light source registration,
- ambient emitter setup,
- Lua callbacks (`LoadGridsquare`, `MapObjects.loadGridSquare`).

All of that happens in the same frame that loads the chunk, producing large spikes.

### Fix
A new singleton `ApocBRClientChunkLoadScheduler` queues square-finishing tasks and runs a small slice of them each frame.

- `IsoChunk.doLoadGridsquare()` now calls `ApocBRClientChunkLoadScheduler.instance.startChunkLoad(this)` and adds squares to the scheduler with `addSquare(square)` instead of finishing them immediately.
- `IsoObject.addToWorld()` checks `this.apocbrDeferFinishAddToWorld`. When the flag is true it skips the heavy client finish; the scheduler later calls `IsoObject.apocBrFinishClientAddToWorld()` on the deferred square.
- `IsoCell.updateInternal()` calls `ApocBRClientChunkLoadScheduler.instance.process()` each frame on the client.
- When the last queued square of a chunk finishes, `ApocBRClientChunkLoadScheduler.decrementChunkLoad()` calls `chunk.apocBrFinalizeChunkLoad()`.

### Key constants / behavior
- `BUDGET_NANOS = 4_000_000L` (4 ms per frame).
- Tasks are ordered by `distanceSq` to the nearest player, with a monotonic `sequence` tie-breaker so processing is stable.
- `startChunkLoad` / `addSquare` / `endChunkLoad` all return immediately on `GameServer.server`, so the server path is unchanged.

### Important methods
- `ApocBRClientChunkLoadScheduler.process()`
- `ApocBRClientChunkLoadScheduler.SquareTask.run()`
- `IsoObject.apocBrFinishClientAddToWorld()`
- `IsoObject.addToWorld()` (deferred branch)
- `IsoChunk.apocBrFinalizeChunkLoad()`

---

## 2. Time-budgeted chunk loading

### Problem
`IsoChunkMap.updateInternal()` used to load a fixed number of chunks per frame. When several heavy chunks were queued at once the frame time could spike.

### Fix
`updateInternal()` now loads chunks until either the queue is empty or a 4ms budget is exhausted.

```
long budgetStart = System.nanoTime();
boolean processedOne = false;

while (true) {
    if (processedOne && System.nanoTime() - budgetStart >= 4_000_000L) {
        break;
    }

    IsoChunk chunk = IsoChunk.loadGridSquare.poll();
    if (chunk == null) {
        break;
    }
    ...
    chunk.doLoadGridsquare();
    processedOne = true;
}
```

At least one chunk per frame is still processed, which prevents complete stalls while still capping worst-case frame time.

### File
- `IsoChunkMap.java` (`updateInternal()`)

---

## 3. Distance-gated overlay and container work

### Problem
`LoadGridsquare()` runs per-loaded square for every object, updating tile overlays and filling containers even when the square is far from the player and not yet visible.

### Fix
`LoadGridsquarePerformanceWorkaround` now checks `shouldProcessSquare(sq)` on the client. If the square is more than `OVERLAY_DISTANCE_TILES` (24 tiles) away from every player, overlay updates and the container-filling path are skipped for that square. The square is still marked `overlayDone` so it is not revisited.

### Key constants
- `OVERLAY_DISTANCE_TILES = 24.0F`

### File
- `LoadGridsquarePerformanceWorkaround.java`

---

## 4. Reduced cascading lighting work

### Problem
When a chunk finished loading, `IsoChunk.markAdjacentChunksForLighting()` flagged all 8 neighbors with `checkLightingLater_AllPlayers_AllLevels()`. This caused every neighbor chunk to recompute lighting for all 4 player slots and all vertical levels, which is most of the visible chunk map being rebuilt in one frame.

### Fix - IsoChunk
A new helper `checkLightingLater_OnePlayer_LevelRange(playerIndex, minLevel, maxLevel)` was added.

`apocBrMarkChunkForLighting(neighbor)` now:

1. Computes a level overlap between the newly loaded chunk and the neighbor, padded by one level above and below:
   ```
   minZ = max(neighbor.minLevel, this.minLevel - 1)
   maxZ = min(neighbor.maxLevel, this.maxLevel + 1)
   ```
2. Iterates `neighbor.refs` (the `IsoChunkMap` instances that actually reference this neighbor).
3. For each referencing player it sets the lighting flag on the neighbor only for that player and only for the computed level range.

This keeps `markAdjacentChunksForLighting()` server-early-out (`if (GameServer.server) return;`) so server behavior is preserved.

### Fix - LightingJNI
`LightingJNI.update()` previously iterated every chunk in map order and called `updateChunk()` for every flagged chunk. It now:

1. Collects all flagged chunks for the current player into a `PriorityQueue`.
2. The queue is sorted by chunk distance to the player's current chunk position (nearest first).
3. It runs `updateChunk()` until `LIGHTING_BUDGET_NANOS` (2ms) is consumed.
4. Unprocessed chunks keep their `lightCheck[playerIndex]` flag for the next frame.
5. A second pass still updates `lightingNeverDone[playerIndex]` for every loaded chunk using `chunkLightingDone()`, preserving the renderer's "has this chunk ever been lit" check.

### Key constants
- `LightingJNI.LIGHTING_BUDGET_NANOS = 2_000_000L`

### Important methods
- `IsoChunk.checkLightingLater_OnePlayer_LevelRange()`
- `IsoChunk.apocBrMarkChunkForLighting()`
- `IsoChunk.markAdjacentChunksForLighting()`
- `LightingJNI.update()`
- `LightingJNI.apocBrChunkDistanceSq()`

---

## Interaction between systems

The systems are layered so that no single frame pays the full cost of loading a chunk:

1. `IsoChunkMap.updateInternal()` loads at most one chunk per 4ms budget in `doLoadGridsquare()`.
2. `doLoadGridsquare()` registers the chunk with `ApocBRClientChunkLoadScheduler.startChunkLoad()` and queues each populated square.
3. `IsoCell.updateInternal()` runs the scheduler every frame, finishing squares nearest the player until the 4ms budget is consumed.
4. When all squares of a chunk are finished, `apocBrFinalizeChunkLoad()` marks adjacent chunks for lighting using per-player, level-limited flags.
5. `LightingJNI.update()` processes those lighting flags each frame using its own 2ms budget, nearest chunks first.

This means a single chunk load no longer triggers an immediate cascade of object finalization, overlay work, and full-map lighting; the work is spread across multiple frames and prioritized by player proximity.

---

## Tuning knobs

- `ApocBRClientChunkLoadScheduler.BUDGET_NANOS` (4 ms)
- `IsoChunkMap.updateInternal()` hard-coded 4 ms chunk load budget
- `LoadGridsquarePerformanceWorkaround.OVERLAY_DISTANCE_TILES` (24 tiles)
- `LightingJNI.LIGHTING_BUDGET_NANOS` (2 ms)
- `IsoChunk.apocBrMarkChunkForLighting()` +/-1 level padding around the overlap range

---

## Risks / things to watch

- **Lighting lag:** If `LightingJNI.LIGHTING_BUDGET_NANOS` is too low and many chunks are flagged at once, `lightingNeverDone` may stay true for several frames. Visible chunks are processed first, but distant chunks may pop in later than before.
- **Vertical light leaks:** The +/-1 level padding in `apocBrMarkChunkForLighting` covers most real cases. If tall buildings or open roofs cause visible light inconsistencies, increase the padding or change the overlap logic.
- **Scheduler ordering:** `ApocBRClientChunkLoadScheduler` uses `distanceSq` to the nearest player and a `sequence` tie-breaker. If split-screen players are far apart, squares near each player are still compared by distance to the nearest player; in practice `numPlayers` is usually 1.
- **Server path:** All new client-only code early-outs on `GameServer.server` or `GameClient.client` checks where appropriate. The server still runs the original `addToWorld()` and `LuaEventManager.triggerEvent("LoadChunk", this)` immediately.
- **Hot save:** `apocBrFinishClientAddToWorld()` calls `flagForHotSave()`, so deferred finishing also defers hot-save flagging. This is consistent with the rest of the deferred chunk-load design.

---

## Files touched by these optimizations

- `42.19.0-client/src/zombie/iso/ApocBRClientChunkLoadScheduler.java` (new)
- `42.19.0-client/src/zombie/iso/IsoCell.java`
- `42.19.0-client/src/zombie/iso/IsoChunk.java`
- `42.19.0-client/src/zombie/iso/IsoChunkMap.java`
- `42.19.0-client/src/zombie/iso/IsoObject.java`
- `42.19.0-client/src/zombie/iso/LightingJNI.java`
- `42.19.0-client/src/zombie/LoadGridsquarePerformanceWorkaround.java`
