# ServerMap unload and zombie population performance plan

## Current signal

The current high-confidence unload signal is:

- `serverMapPost` contains the vanilla same-tick unload path.
- The new `serverMapPostPhases` telemetry shows the spike is in `cellUnload`, not in `zombiePost`, `updateSaved`, relevance checks, or `loadedCells.remove`.
- In the local teleport-pressure test, `cellUnload` ran 14 times with `avgMs=27.64` and `maxMs=67.80`.
- The unload detail with the scariest local max was `chunkZombiePop.maxMs=40.76`.
- `saveUnloadedWrite` was small in that local test, so the immediate hitch is synchronous world detachment, not disk write.

The current load-side signal is:

- `serverMapPre -> load2 -> load2RecalcAll2 -> load2DoLoadGridSquare` is the biggest teleport/load spike.
- `LoadGridsquare` Lua is visible during load and should be treated as a gameplay-consistency path until we identify individual mod offenders.

The current world-simulation signal is:

- At higher online counts, the steady average tick cost moves toward `gameState -> stateIsoWorld -> stateMoveUpdate`.
- Active zombie population appears to amplify average tick pacing, even when load/unload spikes are not the only problem.

## Task list

1. Batch zombie population save requests during unload. Status: implemented with pending popman-cell dedupe.
2. Audit and optimize `ZombiePopulationManager.removeChunkFromWorld`. Status: partially implemented by removing repeated moving-object getter calls and reducing save-lock churn; native virtualization remains main-thread.
3. Optimize adjacent-square disconnection during chunk detach. Status: implemented by caching adjacent-square lookups.
4. Remove avoidable server-side client/render work from unload. Status: first pass implemented for rain/water/puddle geometry cleanup.
5. Batch or defer vehicle database updates during unload.
6. Reduce duplicate work while preserving same-tick unload semantics.
7. After unload work, investigate active zombie population pressure on world simulation.

## 1. Batch zombie population save requests during unload

### Where it happens

`IsoChunk.beginRemoveFromWorld()` calls:

```java
ZombiePopulationManager.instance.removeChunkFromWorld(this);
if (!GameClient.client) {
    int popmanCellX = (int)Math.floor(this.wx / 32.0);
    int popmanCellY = (int)Math.floor(this.wy / 32.0);
    ZombiePopulationManager.instance.requestSaveCell(popmanCellX, popmanCellY);
}
```

### Why it can tank performance

Population manager cells are much larger than chunks. A popman cell is keyed by `chunk / 32`, while server unload operates chunk-by-chunk under an 8x8 server cell. During teleport or login/logout churn, many chunks from the same popman cell can unload in the same tick. That means the server can repeatedly request saving the same population cell.

Even if `requestSaveCell` internally dedupes, the call path still does repeated coordinate math, repeated queue/hash work, and repeated pressure on the population subsystem. If it does not dedupe efficiently, it can cause redundant work or queue growth.

### Optimization direction

Deduplicate popman save requests per unload batch. The likely lowest-risk shape is:

- During `ServerCell.Unload()`, collect unique `(popmanCellX, popmanCellY)` pairs for all chunks being removed.
- Allow each chunk to still run `ZombiePopulationManager.removeChunkFromWorld(this)` immediately, because that mutates live population state.
- Move `requestSaveCell` out of `IsoChunk.beginRemoveFromWorld()` or guard it so the same popman cell is requested once per `ServerCell.Unload()`.

If moving the call is too invasive, add a per-tick or per-thread-local dedupe helper around `requestSaveCell`.

### What to measure

Add or inspect telemetry for:

- number of `requestSaveCell` calls
- number of unique popman cells requested
- time spent in `requestSaveCell`
- relation between `requestSaveCell` and `chunkZombiePop`

Success means `chunkZombiePop` total cost drops during multi-chunk unloads without changing zombie persistence behavior.

## 2. Audit and optimize `ZombiePopulationManager.removeChunkFromWorld`

### Where it happens

`IsoChunk.beginRemoveFromWorld()` calls:

```java
ZombiePopulationManager.instance.removeChunkFromWorld(this);
```

This is currently recorded as `unloadDetails.chunkZombiePop`.

### Why it can tank performance

Telemetry already shows this as the largest unload sub-spike. In the teleport test, `chunkZombiePop.maxMs=40.76`. If several chunks unload in one `ServerMap.postupdate()` tick, that cost can stack into a large `cellUnload` and `serverMapPost` spike.

Likely risk patterns inside the population manager:

- scanning population records broader than the single chunk
- repeated conversion between chunk, world, and population-cell coordinates
- repeated save marking for the same larger cell
- removing or reconciling zombie records one chunk at a time when the enclosing server cell is being unloaded as a batch
- synchronization or collection churn around population data

### Optimization direction

Inspect `ZombiePopulationManager.removeChunkFromWorld` directly and classify its work:

- strictly per chunk and required immediately
- per popman cell and dedupeable
- save/persistence marking and deferable
- broad scans that can be replaced by keyed lookup

Best possible improvement would be to convert any broad scan into a direct lookup keyed by chunk or popman cell. If the manager keeps per-cell collections, unload should hit the exact cell and exact chunk bucket, not scan unrelated records.

Implemented first-pass local improvements:

- `requestSaveCell` now dedupes pending popman cell saves before scanning all loaded zombies.
- The per-chunk square scan now caches `sq.getMovingObjects()` once per square.
- `saveLock` is acquired lazily only when a zombie actually needs virtualization, and then held for the remaining chunk virtualization work instead of lock/unlock around every individual zombie.

This does not make `n_addZombie` asynchronous. That native/global mutation remains on the main thread because it touches population state and real zombie world detachment.

### What to measure

Split `chunkZombiePop` into inner phases if source allows:

- remove chunk records
- request save cell
- broad scan / query
- allocation or list cleanup

Success means the max per chunk stops producing 40ms-class spikes.

## 3. Optimize adjacent-square disconnection during chunk detach

### Where it happens

`IsoChunk.removeSquareFromWorld()` calls:

```java
this.disconnectFromAdjacentChunks(sq);
```

`disconnectFromAdjacentChunks` checks boundary squares and repeatedly calls `sq.getAdjacentSquare(direction)`.

### Why it can tank performance

Each individual call is tiny, but it runs at square scale. In a teleport unload test, square-level details ran tens of thousands of times. On production unload bursts, this can become a real aggregate tax.

The method currently does this pattern repeatedly:

```java
if (sq.getAdjacentSquare(d1) != null && sq.getAdjacentSquare(d1).chunk != sq.chunk) {
    sq.getAdjacentSquare(d1).setAdjacentSquare(d2, null);
    sq.getAdjacentSquare(d1).s = null;
}
```

That is the same adjacent lookup up to three times per direction. For eight directions across boundary squares, this is pure duplicate work.

### Optimization direction

Cache the adjacent square once per direction:

```java
IsoGridSquare adjacent = sq.getAdjacentSquare(d1);
if (adjacent != null && adjacent.chunk != sq.chunk) {
    adjacent.setAdjacentSquare(d2, null);
    adjacent.s = null;
}
```

This keeps behavior identical and reduces method calls and pointer chasing. It is a low-risk local optimization.

Implemented: `IsoChunk.disconnectFromAdjacentChunks` now caches the adjacent square once for each direction before clearing the reverse link.

### What to measure

Watch:

- `unloadDetails.squareAdjacent.avgMs`
- `unloadDetails.squareAdjacent.maxMs`
- total calls/units during burst unloads

Success may be modest per call but meaningful in aggregate.

## 4. Remove avoidable server-side client/render work from unload

### Where it happens

`IsoChunk.beginRemoveFromWorld`, `removeSquareFromWorld`, and `finishRemoveFromWorld` contain multiple branches that are only useful for client, single-player, render, or local simulation paths.

Known guarded examples:

- `GameClient.client && GameClient.instance.connected`
- `!GameServer.server`
- render/cutaway/visibility cleanup in `finishRemoveFromWorld`
- client animal instance removal

### Why it can tank performance

The dedicated server should not pay for render-facing cleanup, client item notifications, local meta vehicle simulation, or visual chunk resources. Many of these branches are already guarded, but this area deserves a second pass because decompiled vanilla code is shared across client, single-player, and server paths.

Even when a branch is skipped, expensive condition setup can remain. More importantly, if a server path accidentally runs a client-oriented operation, it can add hidden cost and risk.

### Optimization direction

Audit the unload path with a dedicated-server lens:

- `IsoChunk.beginRemoveFromWorld`
- `IsoChunk.removeSquareFromWorld`
- `IsoChunk.finishRemoveFromWorld`
- `IsoObject.removeFromWorldToMeta`
- moving/static object removal methods
- vehicle unload hooks

For each branch, classify as:

- required for server authority/state correctness
- required for save/persistence
- client/render only
- single-player only
- redundant after server-specific patches

Then add explicit `GameServer.server` fast paths where safe.

Implemented first-pass local improvement:

- `IsoChunk.removeSquareFromWorld` now skips `RainManager.RemoveAllOn`, `sq.clearWater()`, and `sq.clearPuddles()` on dedicated servers.
- `RainManager.RemoveAllOn` only detaches `IsoRaindrop` and `IsoRainSplash` presentation objects.
- `clearWater` and `clearPuddles` release `IsoWaterGeometry` and `IsoPuddlesGeometry` pool objects. Searches show these are render/terrain geometry paths, while gameplay water availability is still driven by object `hasWater()` state, not this cached geometry cleanup.
- Client and single-player behavior is preserved because the cleanup still runs when `!GameServer.server`.

The larger unload path was also audited. `finishRemoveFromWorld` already guards FBO render chunks, occlusion, cutaways, visibility polygon data, and corpse render data behind `!GameServer.server`. `removeSquareFromWorld` already guards client animal instance removal and single-player vehicle-meta update paths. The object and moving-object removal calls should remain server-side because they detach ECS/entities, update process lists, remove scheduler entries, stop world emitters/lights, and preserve persistence hooks.

### What to measure

Use existing unload details first:

- `squareMoving`
- `squareObjects`
- `squareStatic`
- `finishVehicles`
- `finishChunkMeta`

If one grows under production pressure, add inner telemetry there before changing behavior.

## 5. Batch or defer vehicle database updates during unload

### Where it happens

`ServerCell.Unload()` does:

```java
for (int i = 0; i < chunk.vehicles.size(); i++) {
    BaseVehicle vehicle = chunk.vehicles.get(i);
    VehiclesDB2.instance.updateVehicle(vehicle);
}
```

There is also vehicle-related logic inside `IsoChunk.removeSquareFromWorld` and `finishRemoveFromWorld`.

### Why it can tank performance

Vehicle DB updates are persistence-sensitive and may touch heavier serialization/index logic. Vehicle-heavy map areas can turn unload into synchronous vehicle persistence work. Even if each update is small, unloading many chunks in one tick can stack the cost.

Potential risk patterns:

- same vehicle updated more than once during overlapping chunk/cell cleanup
- immediate DB update for every vehicle instead of batching
- update happening even when vehicle state did not materially change
- update interleaved with world detach, preventing a cheaper batch handoff

### Optimization direction

Add a vehicle-specific unload telemetry phase before changing behavior:

- number of vehicles seen during `ServerCell.Unload`
- time spent in `VehiclesDB2.instance.updateVehicle`
- duplicate vehicle IDs per cell unload

Then consider:

- dedupe by `vehicleId` per `ServerCell.Unload`
- batch update call if `VehiclesDB2` supports one
- enqueue vehicle persistence after detach if the vehicle object remains safe to serialize
- skip update for vehicles already updated or not dirty, if a reliable dirty flag exists

### What to measure

Add or watch:

- `vehicleUnloadUpdate.calls`
- `vehicleUnloadUpdate.units`
- `vehicleUnloadUpdate.avgMs`
- `vehicleUnloadUpdate.maxMs`

Success means vehicle-heavy unloads no longer produce unexplained `cellUnload` spikes.

## 6. Reduce duplicate work while preserving same-tick unload semantics

### Where it happens

The current decision is to keep vanilla same-tick unload:

```java
chunk.removeFromWorld();
```

which drains:

```java
beginRemoveFromWorld();
while (!processRemoveFromWorldSquares(Integer.MAX_VALUE)) {
}
finishRemoveFromWorld();
```

### Why it can tank performance

Same-tick unload is simple and behaviorally safer than spreading detach across frames, but it means all duplicate work in the path stacks into a single frame:

- multiple chunks requesting the same population save
- repeated adjacent lookups
- repeated room/zone detach operations
- repeated object callbacks
- repeated vehicle DB updates
- repeated global manager removals

The issue is not only the amount of work; it is the lack of batching at the server-cell boundary.

### Optimization direction

Keep the visible behavior same-tick, but make the cell unload more batch-aware:

- build per-cell sets for popman save requests
- dedupe vehicle IDs
- cache repeated global values such as `GameServer.server`, cell bounds, and chunk coordinates where meaningful
- reverse-iterate or swap-remove lists if removals become measurable
- reduce repeated getters inside square loops
- avoid per-square work when square collections are empty and the operation is known to be a no-op

This is the best local direction because it preserves the main semantic guarantee: once `ServerCell.Unload()` returns, the cell is detached.

### What to measure

The key telemetry is now:

- `serverMapPostPhases.cellUnload`
- `serverMapPostPhases.loop`
- `unloadDetails.chunkZombiePop`
- `unloadDetails.squareAdjacent`
- `unloadDetails.squareObjects`
- `unloadDetails.squareMoving`
- `unloadDetails.finishVehicles`

Success means `cellUnload.maxMs` drops while `serverMapPostPhases.zombiePost` and `updateSaved` remain small.

## 7. Follow-up: active zombie population and world simulation

### Where it appears

Production telemetry has shown that when online count and active zombie population rise, the steady average tick cost is dominated by:

- `gameState`
- `stateIsoWorld`
- `stateMoveUpdate`

Zombie counts do not only affect zombie AI. They can also affect moving object lists, network relevance, ownership/auth calculations, LOS pressure, pathfinding, population updates, and packet fanout.

### Why it can tank average tick pacing

Load/unload causes spikes. Active zombie population causes sustained average pressure.

Likely scaling patterns:

- `players * active moving objects`
- `connections * relevant zombies`
- per-tick scans over all moving objects where only players/vehicles/animals are needed
- zombie auth grid rebuild/query pressure
- relay packet generation and extra-all marking
- population manager update batches
- pathfinding or LOS work that rises with active zombies near players

The earlier zombie scheduler optimization reduced some direct zombie update work, but zombies can still participate in global lists and networking scans.

### Investigation direction after unload

Once unload spikes are reduced, focus on world simulation:

- split `stateMoveUpdate` by moving object type
- measure player, zombie, vehicle, animal, and misc object update cost separately
- find global moving-object scans that scale with active zombies
- identify places where zombies should be indexed spatially instead of scanned linearly
- verify whether network relevance uses grid indexes consistently or falls back to list scans
- measure Lua callbacks tied to zombie creation, movement, death, and population events

### What to measure

Useful fields already nearby:

- `stateMoveUpdate`
- `zombieNet.auth.updateCalls`
- `zombieNet.auth.avgUpdateZombies`
- `zombieNet.auth.avgUpdateMs`
- `zombieNet.relay.avgActive`
- `zombieNet.relay.avgCandidates`
- `zombiePop.avgMs`
- `los.starved`
- `los.avgMs`

Potential new telemetry:

- moving object update time by Java class/category
- active moving object counts by category
- per-player/per-connection relevance scan time
- zombie list scan call count and scanned unit count

Success means tick average no longer grows linearly or worse with active zombie count at 40-50 players.
