# Diagnosis: walking stutter caused by chunk loading and asset integration

**Goal:** explain the full path a server chunk takes from "player moved one chunk over" to "chunk is rendered and part of normal world simulation", identify why this produces hitches, and point out places where we can intervene without breaking gameplay.

**Version:** Project Zomboid 42.19.0 client sources (decompiled `42.19.0/decompiled` + patchable `42.19.0-client/src`).

---

## 1. Trigger: the player crosses a chunk boundary

The world is divided into `IsoChunk`s of 8x8 squares. Each player has an `IsoChunkMap` that tracks the 13x13 (`chunkGridWidth = 13`) chunk window around the player.

When the player moves, `ProcessChunkPos` in `IsoChunkMap` runs:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoChunkMap.java:836-927
```

If the player chunk coordinate changed by less than the window size, it calls one of `LoadLeft`, `LoadRight`, `LoadUp`, `LoadDown`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoChunkMap.java:547-671
```

Those methods:

1. Shift the existing `chunksSwapA/B` buffers one cell.
2. Remove the row/column that fell off the window (`removeFromWorld`, `ChunkSaveWorker.Add`).
3. Call `WorldSimulation.scrollGround*` to shift the physics broadphase.
4. Queue the new edge row/column through `LoadChunkForLater`.
5. Swap the buffers and call `LightingThread.instance.scroll*`.

`LoadChunkForLater` either reuses an existing `SharedChunks` entry or grabs a chunk from `chunkStore`, assigns it, and submits a `WorldStreamer` job:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoChunkMap.java:316-347
```

**Important:** the whole shift/swap is done synchronously on the main thread inside `bSettingChunk.lock()`. The actual disk/network load is offloaded, but the bookkeeping and the chunk that is being pushed out of the world are not.

---

## 2. From server bytes to an `IsoChunk` object (background)

`WorldStreamer` is a daemon thread (`World Streamer`) that runs `threadLoop`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\WorldStreamer.java:321-435
```

On the client it does:

- `sendRequests()` / `updateMain()` sends `RequestZipList` packets to the server.
- `receiveChunkPart` reassembles the compressed chunk bytes.
- `loadReceivedChunks` decompresses the data and calls `DoChunk(chunk, requestBB)`.
- `DoChunkAlways` calls `IsoChunk.LoadOrCreate` (or `LoadBrandNew` on failure), then, if the chunk is still referenced, `chunk.loadInWorldStreamerThread()` and finally adds it to the main-thread queue `IsoChunk.loadGridSquare`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\WorldStreamer.java:454-512
```

### 2.1 Deserialization

`IsoChunk.LoadFromDiskOrBufferInternal` reads the chunk byte buffer. It loops over the 8x8 square grid and every level (up to 64 levels), creates `IsoGridSquare`s, and calls `gs.load(...)` for every non-empty square. Vehicles are loaded separately in an inner loop:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3425-3661
```

This is already background work, but it allocates a lot of objects and can pressure the GC before the main thread even sees the chunk.

### 2.2 World-streamer preparation

`loadInWorldStreamerThread` creates squares on level 0 if missing, runs `RecalcProperties` per square, runs `RecalcAllWithNeighbours(true, chunkGetter)` per square, and marks all squares `propertiesDirty = true`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:2372-2444
```

This is also background work, but it does the first pass of collision/pathfinding/vision recalculation for the whole chunk and for some surrounding squares (via `ensureNotNull3x3`).

---

## 3. Main-thread hand-off: `IsoChunkMap.update`

Every frame, `IsoCell.updateInternal` calls `chunkMap[n].update()` for every active player:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoCell.java:4160-4179
```

`IsoChunkMap.updateInternal` pulls chunks from `IsoChunk.loadGridSquare` and limits itself to:

```java
count = 1 + count * 3 / chunkGridWidth;
```

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoChunkMap.java:147-206
```

For each chunk it:

1. Tries to place it into every active player's chunk map with `setChunkDirect` (under `bSettingChunk.lock`).
2. Marks it `loaded = true`.
3. Calls `chunk.doLoadGridsquare()` under the same lock.
4. On the client, sends `VehicleRequest` for every vehicle in the chunk.
5. Sets `dirtyRecalcGridStackTime = 20.0F` for every player.

`setChunkDirect` also runs `updateBuildings()` and `setCache()`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0-client\src\zombie\iso\IsoChunkMap.java:468-524
```

**Critical:** `doLoadGridsquare` runs synchronously on the main thread. If one loaded chunk is expensive, the whole frame waits for it.

---

## 4. Integration into world simulation (the expensive loops)

`doLoadGridsquare` is where most of the visible hitch lives:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3663-3931
```

### 4.1 Main-thread chunk setup

- If not on the server it calls `loadInMainThread()`, which looks up all 8 surrounding chunks, creates missing edge squares, recalculates navigation for every border square, and recalculates adjacency with neighbours. It also calls `getCutawayData().recreateLevel(z)` for every level and marks **all 8 surrounding chunks** for a full lighting update:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:2566-2865
```

### 4.2 Object insertion loop

After `update()` (blending/attachments/vehicle-story setup), `doLoadGridsquare` iterates every level, every square in the 8x8 grid, and every object on that square:

```java
for (int zz = this.minLevel; zz <= this.maxLevel; zz++) {
    for (int x = 0; x < 8; x++) {
        for (int y = 0; y < 8; y++) {
            IsoGridSquare square = this.getGridSquare(x, y, zz);
            if (square != null && !square.getObjects().isEmpty()) {
                for (int ix = 0; ix < square.getObjects().size(); ix++) {
                    IsoObject obj = square.getObjects().get(ix);
                    obj.addToWorld();
                    ...
                }
                ...
                MapObjects.loadGridSquare(square);
                LuaEventManager.triggerEvent("LoadGridsquare", square);
                LoadGridsquarePerformanceWorkaround.LoadGridsquare(square);
            }
            if (square != null && !square.getStaticMovingObjects().isEmpty()) {
                for (int ix = 0; ix < square.getStaticMovingObjects().size(); ix++) {
                    IsoMovingObject objx = square.getStaticMovingObjects().get(ix);
                    objx.addToWorld();
                }
            }
        }
    }
}
```

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3768-3818
```

`IsoObject.addToWorld()` does:

- `createContainersFromSpriteProperties()`
- Adds every container to `processItems`
- Registers generator-powered objects
- Adds ambient emitters (fridge hum, water drip, tree ambiance, tent, custom ambient sounds)
- Adds light sources to the world
- Flags for hot save

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoObject.java:4641-4681
```

`LoadGridsquarePerformanceWorkaround.LoadGridsquare` runs **per square** and iterates every object again to fill containers and update overlay sprites:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\LoadGridsquarePerformanceWorkaround.java:23-43
```

### 4.3 Manager registration

After the object loop, the chunk is registered with the world-simulation subsystems:

```java
MapCollisionData.instance.addChunkToWorld(this);
AnimalPopulationManager.getInstance().addChunkToWorld(this);
ZombiePopulationManager.instance.addChunkToWorld(this);
if (PathfindNative.useNativeCode) {
    PathfindNative.instance.addChunkToWorld(this);
} else {
    PolygonalMap2.instance.addChunkToWorld(this);
}
IsoGenerator.chunkLoaded(this);
LootRespawn.chunkLoaded(this);
```

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3829-3841
```

On the client it also registers room lights, runs `randomizeBuildingsEtc`, and adds the chunk to `VisibilityPolygon2`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3843-3882
```

`MapCollisionData.addChunkToWorld` is a synchronous 8x8 square scan that computes collision bits and calls a native update:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\MapCollisionData.java:217-253
```

`PathfindNative` and `PolygonalMap2` queue the update to their own threads, but the enqueue and allocation happen on the main thread.

---

## 5. Rendering / lighting pipeline

Lighting is handled by `LightingJNI` (native) with a Java harness. The main thread calls `LightingThread.instance.update()` every frame, which calls `LightingJNI.update()`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\LightingThread.java:85-91
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\GameWindow.java:724-726
```

`LightingJNI.update()` iterates over every chunk in every player's 13x13 window. If `chunk.lightCheck[playerIndex]` is true, it calls `updateChunk(playerIndex, chunk)`:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\LightingJNI.java:887-959
```

`updateChunk` loops over every level and every square in the 8x8 chunk and performs the square lighting update:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\LightingJNI.java:609-639
```

When a chunk is loaded, the flags are set by `checkLightingLater_*` methods. `loadInMainThread` marks **all adjacent chunks** with `checkLightingLater_AllPlayers_AllLevels()`, so a single chunk load can cause up to 9 chunks x 64 levels x 64 squares of lighting work on the main thread.

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:2822-2853
```

The actual FBO cache that the renderer uses is managed by `FBORenderChunkManager`. Each chunk has `FBORenderLevels` per player. When lighting changes, the render levels are invalidated (e.g. `buildingsChanged()` calls `invalidateAll(18496L)` and `getCutawayData().invalidateAll()`):

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\LightingJNI.java:1227-1244
```

During `IsoCell.RenderTiles` / `FBORenderCell`, if a render level is dirty, the engine renders the entire chunk into an off-screen FBO before it can be drawn in the world:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\fboRenderChunk\FBORenderChunkManager.java:134-172
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\fboRenderChunk\FBORenderChunkManager.java:257-348
```

This first-frame render is another burst of CPU/GPU work concentrated on the exact frame the new chunk becomes visible.

There is also a `delayObjectRender` debug option that staggers object rendering by up to 5 frames using `renderFrame`/`loadedFrame`, but it is not the default behavior:

```
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\IsoChunk.java:3926-3928
@C:\Users\x316739\Downloads\apocalise-zomboid-patches\apocalipsebr-zomboid-patches\42.19.0\decompiled\zombie\iso\fboRenderChunk\FBORenderCell.java:1255-1258
```

---

## 6. Why the stutter happens

All the steps above are necessary for correctness, but they are **not evenly distributed** over frames. The most likely contributors to a walking hitch are:

1. **Synchronous main-thread chunk integration.** `doLoadGridsquare` and `loadInMainThread` run inside the main simulation frame. The throttle `count = 1 + count * 3 / chunkGridWidth` only limits the **number** of chunks, not their cost. One expensive chunk can stall the frame.
2. **Per-object insertion loops.** Every static object calls `addToWorld()`, which may create containers, ambient emitters, and light sources. The `LoadGridsquarePerformanceWorkaround` then re-scans every object to fill containers and update overlays. Dense city chunks have many objects per square.
3. **Cascading neighbor work.** `loadInMainThread` recalculates nav/vision for the whole chunk border and all adjacent chunks, and marks 9 chunks for a full lighting update.
4. **Lighting update on the main thread.** `LightingJNI.update()` and `updateChunk()` walk 8x8xlevels per chunk. With 9 chunks flagged, the cost can be 9x the naive expectation.
5. **FBO cache cold start.** Newly loaded chunks dirty their render levels. The first time the renderer needs them, it renders the chunk to an FBO, which is heavy and can stall the GPU pipeline.
6. **Container/item processing and sprite loading.** Filling containers and resolving overlay sprites can touch item tables and sprite/texture data. If textures are not resident, the first render triggers a load/upload.
7. **Lua events.** `LoadGridsquare` is fired per loaded square and many mods hook it. A slow mod turns the whole chunk load into a hitch.
8. **Allocation/GC pressure.** The background thread creates thousands of objects; the main thread then creates additional managers/list entries. If the GC happens to run right after a load, the pause is attributed to the walk.
9. **Chunk unload cost.** `LoadLeft/Right/Up/Down` and `Unload` remove the outgoing chunks from the world, call `removeFromWorld`, and queue them to `ChunkSaveWorker`. That can also produce hitches, especially on weak storage.

---

## 7. Where to operate without breaking gameplay

These are the safest conceptual intervention points. Any actual patch should be measured against the GameProfiler probes already in the code.

### 7.1 Spread the main-thread work over more frames

- The throttle in `IsoChunkMap.updateInternal` is chunk-count based. Consider making it **cost-based** or adding a per-frame budget that stops mid-loop if the budget is exceeded. The loop must remain safe to resume because `doLoadGridsquare` currently assumes it runs atomically.
- Make `doLoadGridsquare` resumable: split the object insertion loop into small batches and finish the rest on subsequent frames. This is the highest-impact change because it directly reduces the per-frame spike.

### 7.2 Defer or lazy-load non-critical per-object work

- `LoadGridsquarePerformanceWorkaround.LoadGridsquare` is per-square and does container filling and overlay sprite resolution. On the client, the server already sent the container contents; the client may not need to run `ItemPickerJava.fillContainer` at all. Skipping or deferring this would remove a large per-object cost.
- `IsoObject.addToWorld` adds ambient emitters and light sources immediately. Static emitters could be registered lazily when the player is near enough to hear/see them, instead of at chunk load time.
- `addToWorld` also flags the chunk for hot save. This is unnecessary for freshly loaded chunks and could be delayed until the object is actually modified.

### 7.3 Reduce cascading lighting work

- `loadInMainThread` marks all 8 neighbors with `checkLightingLater_AllPlayers_AllLevels()`. Most of the time only the newly loaded chunk and its border rows need a full recalc. The interior of already-loaded neighbors does not need to be fully recalculated from scratch unless the new chunk actually changed their light sources.
- Lighting flags are per-player (`lightCheck[playerIndex]`). On single-player clients there is only one active player, so some of the `AllPlayers` flags are overkill.

### 7.4 Warm the FBO cache before the chunk is visible

- The `FBORenderChunkManager` already has the machinery to render chunks to FBOs. A low-resolution or placeholder FBO could be generated in the background for newly loaded chunks, or the first render could be staggered across frames so that one chunk does not render all its levels at once.
- Be careful: `FBORenderChunkManager` uses OpenGL state shared with the main renderer, so background GL work must be done on the render thread or with proper synchronization.

### 7.5 Offload more manager registration

- `MapCollisionData.addChunkToWorld` does a synchronous 8x8 scan and a native call. It could be moved to the world-streamer thread or made incremental, because the player is not interacting with the new chunk until it is actually rendered.
- `PathfindNative`/`PolygonalMap2` already queue the work to background threads. The main thread cost is mostly the task allocation. Keeping those as-is is probably fine; focus on `MapCollisionData` and `VisibilityPolygon2` if they show up in profiling.

### 7.6 Limit Lua event cost

- `LoadGridsquare` and `LoadChunk` are fired for every square/chunk. If modding is part of the target environment, provide an opt-in fast path or a C-style callback that skips the Lua dispatcher for chunks that do not need it. Even without mods, the dispatcher itself has overhead.

### 7.7 Reduce memory churn

- `IsoGridSquare.getNew` and `IsoObject` factory methods allocate many objects. Object pooling for the transient helper arrays used in `loadInMainThread` and `loadInWorldStreamerThread` can reduce GC pressure. The engine already uses `IsoChunkMap.chunkStore` and `IsoChunkLevel` pools; extending pooling to the per-square load helpers is a low-risk micro-optimization.

### 7.8 Do not touch these areas first

- `WorldSimulation.scrollGround*` and `Bullet.setChunkMinMaxLevel` are physics bookkeeping. They must stay synchronized with the chunk map or collision will be wrong.
- `IsoChunkMap.setChunkDirect` and `bSettingChunk` protect the chunk map from concurrent modification. Removing the lock or changing the buffer swap logic risks crashes.
- The network request logic in `WorldStreamer` is robust; stutters rarely come from network latency itself. Defer network changes until the local main-thread cost is proven small.

---

## 8. Recommended next steps

1. **Instrument the pipeline.** Add timing around:
   - `IsoChunkMap.updateInternal` (total and per chunk)
   - `IsoChunk.doLoadGridsquare` / `loadInMainThread` / `loadInWorldStreamerThread`
   - `LightingJNI.update` and `updateChunk`
   - `FBORenderChunkManager.beginRenderChunkLevel` for newly loaded chunks
   - `LoadGridsquarePerformanceWorkaround.LoadGridsquare`
   - `IsoObject.addToWorld` per object type
2. **Walk in a dense area** while capturing the profiler. Compare the cost of loading a city chunk vs. an empty forest chunk. This tells us whether the object loop, the lighting pass, or the FBO cache is the dominant spike.
3. **Test a minimal patch:** skip `LoadGridsquarePerformanceWorkaround` on the client only and measure. If the hitch drops significantly, that is the cheapest win.
4. **Check whether `LightingJNI.update()` is the bottleneck.** If most of the frame time is inside `LightingJNI.update()` while `lightCheck` flags are true, the fix is to reduce the flag surface (fewer neighbors / fewer levels).
5. **Check FBO cache cost.** If `beginRenderChunkLevel` is the spike, warm or split the FBO generation.
6. **Validate with network server and local single-player.** The client path differs from the server path (e.g. vehicles, container fill, loot respawn), so optimizations should be gated by `GameClient.client` / `GameServer.server` where appropriate.

---

## 9. Key file index for future agents

- Player movement / chunk window shift: `IsoChunkMap` in `42.19.0-client/src/zombie/iso/IsoChunkMap.java`
- Background streaming: `42.19.0/decompiled/zombie/iso/WorldStreamer.java`
- Chunk loading and integration: `42.19.0/decompiled/zombie/iso/IsoChunk.java`
- Per-square container/overlay workaround: `42.19.0/decompiled/zombie/LoadGridsquarePerformanceWorkaround.java`
- Object registration: `42.19.0/decompiled/zombie/iso/IsoObject.java`
- Collision manager registration: `42.19.0/decompiled/zombie/MapCollisionData.java`
- Lighting harness: `42.19.0/decompiled/zombie/iso/LightingThread.java` and `LightingJNI.java`
- FBO render cache: `42.19.0/decompiled/zombie/iso/fboRenderChunk/FBORenderChunkManager.java`
- World simulation update loop: `42.19.0-client/src/zombie/iso/IsoCell.java` (especially `updateInternal` and `RenderTiles`)
- Main loop: `42.19.0/decompiled/zombie/GameWindow.java`

---

*End of diagnosis. This report is intentionally diagnostic; any patch should be preceded by in-game profiling to confirm which of the candidate bottlenecks is dominant in the target environment.*
