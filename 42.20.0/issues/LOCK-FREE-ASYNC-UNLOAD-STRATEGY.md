# Lock-Free Async Unload Strategy

Status: Design discussion, not yet implemented. Picking this back up in a
later session - this document is the checkpoint.

## Goal

Offload the heavy part of cell/chunk unload off the main thread without using
lock/unlock around shared singletons. Instead of "make mutations mutually
exclusive", make the touched structures and objects resilient to concurrent
access: safe to iterate while being mutated, safe to reference while being
torn down, with a single-writer-owns-the-structure model instead of shared
mutable state guarded by a mutex.

This is explicitly not the "wrap everything in `synchronized`" approach from
`42.19.0/issues/async-unload-analisys.md`. That earlier analysis is still
useful as a map of *which* fields are touched, but its proposed fix (locks
everywhere) trades one big main-thread stall for many small ones, since the
main thread reads the same structures (collision, pathfinding, zombie/animal
population) on nearly every tick. Locking those structures for the unload
worker just moves the contention around instead of removing it.

## Key finding: part of this pattern already exists and works

`PathfindNative.removeChunkFromWorld()`
(`42.20.0/decompiled/zombie/pathfind/nativeCode/PathfindNative.java:160-164`)
and `PolygonalMap2.removeChunkFromWorld()`
(`42.20.0/decompiled/zombie/pathfind/PolygonalMap2.java:1730-1736`) do not
mutate any shared state on the calling thread at all:

```java
public void removeChunkFromWorld(IsoChunk chunk) {
    if (this.thread != null) {
        ChunkRemoveTask task = ChunkRemoveTask.alloc().init(this, chunk);
        this.chunkTaskQueue.add(task);
        this.thread.wake();
    }
}
```

The caller allocates an immutable task, pushes it onto a
`ConcurrentLinkedQueue`, and wakes a dedicated single-consumer thread that
owns all mutation of the pathfinding data structures. No lock anywhere in
this call. This is the exact pattern we want to generalize: single-writer
owns the structure, producers only ever hand off intent objects.

This means: for pathfinding/polygonal map chunk removal, the "heavy lift
already runs off the main thread" question is already answered - yes, and it
has been for a while. The remaining unload cost near this code path is
whatever `IsoChunk.removeFromWorld()` still does synchronously around it.

## Per-singleton assessment

### `MapCollisionData` - non-issue on server

`MapCollisionData.removeChunkFromWorld()`
(`42.20.0/decompiled/zombie/MapCollisionData.java:255-259`) is a server-side
no-op:

```java
public void removeChunkFromWorld(IsoChunk chunk) {
    if (!this.client) {
        ;
    }
}
```

The 42.19.0 risk doc's "HIGH risk" rating for this class does not apply to
server-side unload in 42.20.0. Drop it from the worry list for this specific
work item.

### `PathfindNative` / `PolygonalMap2` - already solved

See above. No further work needed here; this is the reference
implementation for every other singleton below.

### `ZombiePopulationManager` - needs conversion, has a clear template

`removeChunkFromWorld()`
(`42.20.0/decompiled/zombie/popman/ZombiePopulationManager.java:351-409`)
still uses `saveLock.lock()`/`unlock()` around each zombie virtualization:

```java
saveLock.lock();
try {
    n_addZombie(...);
    realZombie.removeFromWorld();
    realZombie.removeFromSquare();
    i--;
} finally {
    saveLock.unlock();
}
```

Proposed conversion: build an immutable "virtualize this zombie" command per
candidate, push to a `ConcurrentLinkedQueue`, drain on one dedicated consumer
thread (or a single controlled point in the main loop), exactly mirroring
`PathfindNative`'s `ChunkRemoveTask`. Removes the lock entirely.

### `AnimalPopulationManager` - same shape, smaller scope

`removeChunkFromWorld()`
(`42.20.0/decompiled/zombie/characters/animals/AnimalPopulationManager.java:60-`)
has the same virtualization pattern. Its `newChunks` `TIntHashSet` (flagged
HIGH risk in the 42.19.0 doc) is only mutated in `addChunkToWorld`, never in
`removeChunkFromWorld` - it is not actually in the unload hot path and can be
dropped from this specific work item's scope.

### Shared object lists - the genuinely hard part

`sq.getMovingObjects()`, `IsoWorld.instance.currentCell.getSurvivorList()` /
`getVehicles()`, and `RainManager`'s splash/drop lists are read from many
unrelated call sites (AI tick, rendering, LOS) that don't funnel through one
owner the way pathfinding does. Two separate techniques are needed here, not
one:

1. **Container safety** (no crash on concurrent iterate+remove): swap the
   handful of lists touched by unload from `ArrayList` to
   `CopyOnWriteArrayList`. Iteration then always sees a stable snapshot - no
   `ConcurrentModificationException`, no index-shift corruption. The copy
   cost on removal is acceptable since unload removal frequency is far lower
   than per-tick reads of these lists.
2. **Object validity, not just container validity**: copy-on-write protects
   the list, not an object reference a reader already grabbed. If a worker
   nulls `obj.current`/`obj.last` while the main thread mid-reads that same
   object, that's a race on the object's own fields, independent of the
   list. Fix: add a `volatile boolean isUnloading` (or state enum) on
   `IsoMovingObject`, set **before** any field is torn down, and have every
   hot-path reader check-and-skip instead of trusting the reference is fully
   live. This is a tombstone pattern, not a lock, and directly targets the
   "loop hits a null/half-removed element" failure mode this whole approach
   is trying to avoid.

## Known correctness issues independent of threading model

These need fixing regardless of which async strategy is chosen:

- **Self-mutating loop** in `IsoChunk.removeFromWorld()`
  (`42.20.0/decompiled/zombie/iso/IsoChunk.java`, moving-object loop around
  the `mov.contains(obj)` check): `obj.removeFromWorld()` can mutate the same
  list being iterated, compensated by manually decrementing the loop index.
  This ordering hazard exists single-threaded already and must stay strictly
  serial per square no matter what threading model is chosen around it.
- **Cross-chunk adjacency**: `disconnectFromAdjacentChunks()` writes into a
  *neighboring* chunk's squares. If two adjacent chunks are ever unloaded
  concurrently, the tombstone/copy-on-write treatment must cover the
  neighbor's squares too, not just the chunk being unloaded on that worker.

## Proposed roadmap (for the later session)

1. Convert `ZombiePopulationManager`'s unload-time virtualization calls to
   the queue + single-consumer-thread pattern already proven by
   `PathfindNative`/`PolygonalMap2`.
2. Apply the same conversion to `AnimalPopulationManager`'s unload-time
   virtualization calls.
3. Switch `sq.getMovingObjects()`, `currentCell.getSurvivorList()`,
   `currentCell.getVehicles()`, and `RainManager`'s lists to
   `CopyOnWriteArrayList` (or equivalent) for the objects touched during
   unload.
4. Add an `isUnloading` tombstone flag to `IsoMovingObject`, set before
   teardown, checked defensively by AI/render/LOS read paths.
5. Fix the self-mutating moving-object loop in `IsoChunk.removeFromWorld()`.
6. Only after 1-5 are in place, evaluate moving the per-square teardown loop
   itself (`RainManager.RemoveAllOn`, `clearWater`, `clearPuddles`,
   room/zone square removal) onto a worker, since by then the objects it
   touches are safe to reference concurrently.

## Explicitly out of scope for now

- No lock/unlock introduction anywhere - this is the point of the exercise.
- No changes to `MapCollisionData` (confirmed non-issue on server).
- No `IsoWorld`/`IsoCell` update-loop parallelism restructuring - still
  requires fresh 42.20.0-specific telemetry before it can be justified, per
  prior discussion.
- No implementation yet. This is a design reference for when this work item
  is picked back up.
