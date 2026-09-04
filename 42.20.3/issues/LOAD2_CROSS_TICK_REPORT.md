# Cross-tick chunk load (load2) report

## Context

Symptom reported: intermittent multi-hundred-millisecond server stalls, worst observed around 800 ms,
subjectively correlated with new zombies appearing as territory loads. Initial hypothesis was that the
moving-object simulation and the zombie relevance/authority calculation were the dominant cost and
should be moved off the main thread, possibly onto virtual threads.

Two detail telemetry samples were used throughout.

| | Sample A (`seq 414`) | Sample B (`seq 522`) |
| --- | --- | --- |
| players / zombies / connections | 31 / 341 / 32 | 51 / 1729 / 55 |
| `world.avgMs` | 98.73 | 111.53 |
| `world.maxMs` | 698.30 | 765.40 |
| `throttleSleep.avgMs` | 21.18 | 5.94 |
| `gameState.avgMs` | 44.77 | 68.40 |
| `stateMoveUpdate.avgMs` | 19.41 | 41.96 |
| `serverMapPre.avgMs` | 26.83 | 15.33 |
| `serverMapPre.maxMs` | 264.38 | 512.69 |
| `serverMapPost.avgMs` | 12.44 | 14.46 |
| `serverMapZombiePost.avgMs` | 1.65 | 4.68 |

The collapse of `throttleSleep` from 21.18 ms to 5.94 ms between the two samples is the clearest
statement that headroom is gone at load.

## What the telemetry ruled out

The zombie network orchestration was not the bottleneck. In Sample B, `relay.avgPostMs` is 4.68 and
`auth.avgUpdateMs` is 4.53, so `updateAuth` is 96.8 % of `NetworkZombiePacker.postupdate` and the
fan-out to the six-worker pool costs 0.15 ms. The pool already gets a whole tick to finish work that
takes `avgConnectionMs 0.04`. Detaching all of it would have recovered 4.2 % of the tick.

Scaling was also better than feared: 341 to 1729 zombies is 5.07x, and auth cost went 1.55 to 4.53 ms,
only 2.9x. The existing 64-tile `authGrid` is doing its job, `avgQueryCandidates` moved 1.06 to 1.15.

The stall lives in chunk streaming. In Sample B, `load2` accounts for 3792 ms of `serverMapPre`'s
3894 ms total, or 97.4 %, and `load2.maxMs` is 512.66. Meanwhile 3968 chunks were loaded and 4502
unloaded in 28.3 s, which is separate churn worth addressing on its own.

## Why virtual threads alone did not fix it

`ServerMap.LoadStuffThread.recalcPool` was already `newVirtualThreadPerTaskExecutor()`, chosen on the
reasoning that workers park in `submitAndWait` and unmount, freeing the carrier. The measured return:

| Sample | main-thread wall in `load2` | worker time in `load2DoLoadGridSquare` |
| --- | --- | --- |
| A | 80 x 81.75 = 6540 ms | 96 x 50.47 = 4845 ms |
| B | 52 x 72.93 = 3792 ms | 62 x 41.95 = 2601 ms |

The parallel phase was not clearing 1x. And `load2PumpIdleWait` shows where the time actually went:

| Sample | idle-wait total | per tick |
| --- | --- | --- |
| A | 18612 x 0.26 = 4839 ms | 19.6 ms |
| B | 12662 x 0.22 = 2786 ms | 11.0 ms |

In Sample B that is 79.5 % of `load2MainPump`'s 3503 ms spent parked on an empty queue.

The conclusion is that Loom converts blocked threads into cheap continuations but does not create CPU
capacity. Workers were not blocking on I/O, they were blocking on a single serialized resource: the
main thread draining `ApocBRMainThreadOrchestrator.queue`. Widening the worker side cannot widen that
funnel. The fix had to be on the consumer, not the producer.

## Failure model

```text
preupdate() reaches load2 with a group of ready cells
  -> recalcAllParallel() dispatches colour group 0 and calls pumpUntil(latch)
  -> main thread is now pinned until every cell in the colour finishes RecalcAll2()
  -> whenever the handoff queue runs dry it sleeps in 1 ms slices (load2PumpIdleWait)
  -> the whole tick becomes as long as the slowest cell in the group (load2.maxMs 512.66)
  -> GameTime reports a huge delta, so EntitySimulation runs several sim ticks at once
  -> the 70 ms packet budget in GameServer.main trips and starts dropping (normal.dropped 30)
  -> MainLoopNetData queues back up (queues.high 16)
  -> ServerLOS backs up (busyMax 36)
  -> the next several ticks are spent catching up, so the stall propagates
  -> if pumpUntil exceeds its 1000 ms timeout, the entire colour group is destroyed:
     cancelLoading = true, isLoaded = false, and the cells are re-queued and reloaded
```

The last step matters: with `load2.maxMs` at 512.66 against a 1000 ms ceiling, the server was already
close to discarding and reloading whole cell groups, which would feed the load/unload churn.

## Design

Objective agreed with the maintainer: not to make an individual cell load faster, but to stop the main
thread stalling. A cell taking 500 ms spread over five healthy ticks is strictly better than one 500 ms
tick, because it keeps every downstream system out of catch-up state.

The model adopted mirrors the existing deferred-unload pipeline: off-thread work whose mutations are
consumed by the main thread in bounded slices, driven both in-tick and from the throttle-sleep idle
window.

`recalcAllParallel(cells)` became `ServerCell.Load2Job`, a persistent object advanced by
`Load2Job.advance(budgetNanos)`:

- `preupdate()` advances it with `LOAD2_MAX_NANOS_PER_TICK` (8 ms, matching `unload.maxMsPerTick`).
- `GameServer.main`'s throttle-sleep window advances it via `ServerMap.advanceLoad2InIdleWindow`,
  ahead of `processDeferredUnloadsInIdleWindow` and charged against the same budget. Load goes first
  because a cell still building is player-visible while a cell still unloading is not.
- Four tick-phase anchors call `ServerMap.drainLoad2MainThreadTasks()` so handoffs are applied
  throughout the tick rather than only at `preupdate`.

Cells that become ready while a job is in flight accumulate in `loaded2` and are admitted to the next
job. They cannot join the running one without breaking its colour partition, and waiting one job cycle
is cheaper than a stall. This was accepted as a deliberate tradeoff.

## Invariants preserved

**Colour barrier across ticks.** `RecalcAll2`'s border pass writes into neighbouring cells' grid-square
storage through `EnsureSurroundNotNull` and `createNewGridSquare`, so two adjacent cells must never run
concurrently. The 4-colour checkerboard by `(wx & 1, wy & 1)` guarantees that within a colour. The
cross-tick version only dispatches the next colour when `pumpFor` reports latch clear **and** queue
empty, so the barrier holds across tick boundaries exactly as it did within a single call.

**`isLoaded` was left doing double duty deliberately.** The obvious design was to gate external
accessors on a new `loadInProgress` flag. That is wrong and fails silently. `IsoCell.getGridSquare` on
the server delegates to `ServerMap.instance.getGridSquare`, and `createNewGridSquare` gates on
`ServerMap.instance.getChunk`, which is the `isLoaded` accessor. `RecalcAll2`'s own border pass goes
`EnsureSurroundNotNull` -> `createNewGridSquare` -> `getChunk`. Gating reads on `loadInProgress` makes
the cell's border scan create no squares at all, corrupting cell seams with no exception and no log.

Half-built reads were already a legal state before this change: `isLoaded` is published early, at the
top of `RecalcAll2`, while the main thread is inside the pump running Lua tasks that query the world.
Consumers already answer null for a not-ready cell and every caller copes. The cross-tick change widens
that window, it does not create it.

`loadInProgress` therefore guards lifecycle transitions only:

1. `ServerCell.beginDeferredUnload` refuses a cell a worker is still building.
2. `ServerMap.postupdate` skips such a cell entirely rather than clearing its `cellMap` entry.
3. `ServerLOS.calcLOS` skips its chunks.

## Timeouts are liveness guards, not work bounds

Three timeouts existed on this path, all sized for a main thread that parks until done. A budgeted pump
invalidates that assumption: measured load2 costs about 386 main-thread tasks and 11.6 ms of
main-thread work per cell (23932 tasks over 62 cells in Sample B), so at an 8 ms budget a colour group
of 8 cells legitimately takes around 12 slices, roughly 1.2 s of wall time. Under the old bounds the
workers would have thrown and the cells would have been cancelled, replacing a stall with a
cancellation storm.

| Bound | Was | Now | Property |
| --- | --- | --- | --- |
| `submitAndWait` per-task | 1000 ms | 30 s for cooperative orchestrators | `apocbr.cooperativeMainThreadTaskTimeoutMs` |
| `chunkFinishLatch.await` in `RecalcAll2` | 1000 ms | 30 s | `apocbr.load2ChunkFinishTimeoutMs` |
| whole-group `pumpUntil` | 1000 ms, destructive | replaced by per-job stall watchdog | `apocbr.load2.jobStallTimeoutMs`, 15 s |

The watchdog restores the property the old timeout was actually there for. A worker that dies or wedges
without counting down would otherwise leave the latch permanently up. `Load2Job.checkStalled` fires only
when a colour group's latch has not moved at all for the timeout, and only then cancels. Budget expiry
means "not finished yet" and never cancels anything.

## LOS is no longer suspended for load2

`preupdate` previously wrapped the whole synchronous load2 block in `ServerLOS.suspend()` /
`resume()`. Unload can do this per call because all its work is synchronous on the main thread, but
load2 workers keep mutating squares between drain slices, so a job spanning ticks would have held LOS
down for seconds. `ServerLOS.calcLOS` already resolved the cell and checked `isLoaded`, so it now also
checks `loadInProgress` and skips just the cells being built.

This also removes `ServerLOS.suspend()`'s busy-wait on `freeSlots` from the load path, which was
measured at `losSuspend.maxMs` 44.67 in Sample B and 19.60 in Sample A.

## Changes by file

### `zombie/ApocBRMainThreadOrchestrator.java`

- `pumpFor(latch, budgetNanos)`: non-parking bounded drain. Runs what is already queued, stops at the
  budget or an empty queue, returns whether the caller may advance. No idle wait at all: an empty queue
  with an outstanding latch returns immediately. The deadline is checked only after a task completes,
  so the budget bounds work started, not how long a single task may run.
- `drainAll()`: empty-queue fast path before `assertMainThread` and `beginDetail`, so an anchor that
  finds nothing costs one volatile read. Now returns the number of tasks applied.
- `draining` reentrancy guard on both. Queued tasks run Lua, and with anchors in place that Lua can
  reach an anchor and re-enter the drain loop from inside a running task. A nested call no-ops.
- Per-instance `taskTimeoutNanos` via a new 4-arg constructor with a `cooperative` flag.
- `pumpUntil` retained, currently unused.

### `zombie/network/ServerMap.java`

- `ServerCell.loadInProgress`, volatile, set on dispatch and cleared in the worker's `finally`.
- `ServerCell.Load2Job`: colour partitioning, `advance`, `dispatchNextColor`, `checkStalled`.
  `latch` and `inFlight` are published **before** submitting to the pool, so a `RejectedExecutionException`
  partway through a colour leaves recoverable state instead of pinning cells `loadInProgress` forever.
- `preupdate`: creates and advances the job, no longer suspends LOS.
- `retireLoad2Job`: end-of-job bookkeeping, shared by the in-tick and idle-window drivers.
- `advanceLoad2InIdleWindow(budgetNanos)`, counterpart to `processDeferredUnloadsInIdleWindow`.
- `drainLoad2MainThreadTasks()`, the anchor entry point.
- `postupdate`: `continue` on `loadInProgress` before the unload block.
- `LOAD2_CHUNK_FINISH_TIMEOUT_MS` default raised to 30 s.

### `zombie/network/ServerLOS.java`

- `calcLOS` chunk resolution also checks `!cell.loadInProgress`.

### `zombie/network/GameServer.java`

- `advanceLoad2InIdleWindow` called in the throttle-sleep window before the unload equivalent.
- Anchors after `gameState`, after `connectionRelevant`, after `objectCleanup`.

### `zombie/iso/IsoCell.java`

- Anchor in `updateInternal` immediately after `safeToAdd = true`.

### `zombie/ApocBRServerTelemetry.java`

- Added `load2IdleAdvance`, `load2JobComplete`, `load2StallCancel`, `load2Anchor`.
- Removed `load2LosSuspend` and `load2LosResume`, now dead.

## Anchor placement rules

Anchor placement is a whitelist, not a sprinkle. Drained tasks are not inert: `LuaEvent.LoadChunk`,
`SGlobalObjects.chunkLoaded`, `MapObjects.loadGridSquare` and
`IsoChunk.erosionMapObjectsLoadGridSquareBatch` all run Lua and can add or remove world objects.

An anchor is only valid where both hold:

1. No world collection is mid-iteration. `MovingObjectUpdateSchedulerUpdateBucket.update` walks its
   bucket by index and `IsoCell.startFrame` iterates `getObjectList()`; a task that adds or removes an
   object inside those loops causes skipped or duplicated objects.
2. No global side-band state is set. `GameTime.perObjectMultiplier` is held at `frameMod` for the whole
   bucket loop and at `mod` (default 8) for the whole of `ProcessIsoObject`. A task drained in that
   window reads an 8x or 16x timestep and silently miscomputes.

The `IsoCell` anchor sits after `safeToAdd = true` specifically because the buckets and
`ProcessIsoObject` have both completed by then, the multiplier is back to 1.0, and `safeToAdd` was just
restored so a task that spawns an object is handled normally.

This is the same hazard already documented in `IsoFeedingTrough.checkContainer`: "one of our callers is
FluidContainer's own update callback, we would be freeing the object under our own call stack."

## Telemetry to watch

| Key | Expected after the change | Reads as a problem if |
| --- | --- | --- |
| `load2.avgMs` | around the 8 ms budget, was 72.93 per call | consistently above budget, budget not respected |
| `load2.maxMs` | around the budget, was 512.66 | large spikes remain, an unsliced path exists |
| `load2PumpIdleWait` | near zero, was 11.0 ms/tick | still significant, something still parks |
| `load2JobComplete` | `units / calls` = slices per job | grows without bound, slicing is not keeping up |
| `load2StallCancel.calls` | zero | non-zero, workers wedging or dying |
| `load2Anchor` | non-zero `calls` and `units` | zero, anchors are not reached or always find empty |
| `load2IdleAdvance` | non-zero when `throttleSleep` is non-zero | zero, idle window not contributing |
| `serverMapPre.maxMs` | well below 512.69 | unchanged, the stall is elsewhere |
| `losSuspend.maxMs` | lower, load2 path removed, was 44.67 | unchanged |
| `world.maxMs` | well below 765.40 | unchanged |

Secondary effects worth confirming, since these were the catch-up amplifiers: `netLoop.normal.dropped`
(was 30), `queues.high` (was 16), `los.busyMax` (was 36).

## Sep 03 21:00 Incident

The telemetry immediately before the disconnect points at a main-thread unload spike, not a heap-full
or allocation-stall event:

- `world.maxMs=1382.83`
- `serverMapPost.maxMs=854.20`
- `unload.maxMs=837.27`
- `load2CellCommitWall.maxMs=726.76`
- `netLoop.high.maxMs=420.37`
- `normal.dropped=301`, followed by logs showing `Server dropped 505 packets`

The previous deferred-unload deadline logic treated overdue cells as mandatory work and processed them
with `Integer.MAX_VALUE` squares, while also allowing overdue cells to expand the per-tick cell/slice
limits. Under player churn this could collapse the intended 8 ms budget into a full-cell teardown on the
main thread. That matches the warning storm from `IsoChunk.removeFromWorld: vehicle wasn't removed from
world`, packet handling after connections were already gone, and the later large GC after map/player
objects became unreachable.

The default now fails slow instead of spiky: overdue unloads stay queued and continue draining under the
same cell/slice/time caps. `apocbr.unload.forceOverdue=true` exists only as an emergency/manual override,
and even then it uses `apocbr.unload.forcedSquaresPerSlice` instead of unbounded whole-cell work.

## Tunables

| Property | Default | Purpose |
| --- | --- | --- |
| `apocbr.load2.maxMsPerTick` | 8 | in-tick drain budget |
| `apocbr.load2.idleEnabled` | true | use the throttle-sleep window |
| `apocbr.load2.idleMaxMs` | 4 | idle-window drain budget |
| `apocbr.loadChunkWorkers` | 3 | off-thread chunk-load workers; raise only if CPU/MMU headroom is proven |
| `apocbr.loadGridSquareThreadCacheSize` | 2048 | per-loader-thread square cache; higher values reduce allocation but enlarge old-gen live set |
| `apocbr.recalcThreadPriority` | 5 | RecalcAll priority; keep below UdpEngine during latency incidents |
| `apocbr.losWorkerThreads` | 4 | parallel LOS workers; raise only if LOS is bottleneck and CPU load is healthy |
| `apocbr.unload.forceOverdue` | false | keep overdue cell unloads bounded instead of forcing full-cell teardown |
| `apocbr.unload.forcedSquaresPerSlice` | 2048 | emergency forced-unload slice cap when `forceOverdue=true` |
| `apocbr.unload.vehicleWarnIntervalMs` | 5000 | rate limit repeated vehicle cleanup warnings during chunk teardown |
| `apocbr.load2.jobStallTimeoutMs` | 15000 | liveness guard on a colour group |
| `apocbr.cooperativeMainThreadTaskTimeoutMs` | 30000 | `submitAndWait` bound for cooperative queues |
| `apocbr.load2ChunkFinishTimeoutMs` | 30000 | `chunkFinishLatch` bound in `RecalcAll2` |

## Not done

- **Not compiled.** `patch.ps1` requires a Project Zomboid install and the game jars, unavailable on
  the development machine. Imports and dangling references were checked by inspection only.
- **Chunk churn.** 3968 loads against 4502 unloads in 28.3 s. This work makes the cost asynchronous but
  does not reduce it. Hysteresis on cell relevance would remove the work outright and is strictly
  cheaper than pipelining it better. This is the single largest remaining item in `serverMapPre` +
  `serverMapPost` + `connectionRelevant`, roughly 37 ms/tick combined in Sample B.
- **`updateServerZombieAuthorityLOS`.** Unrelated to load2 but the largest single item in the tick.
  `IsoPlayer.updateLOS` brute-force iterates the entire global zombie list per player, throttled 1 in 10
  frames, giving `(players / 10) * zombies` iterations per tick: 1057 in Sample A, 8818 in Sample B. A
  two-point fit of `stateMoveUpdate = a*P + b*(P/10)*Z` gives roughly 0.578 ms per player and 1.42 us
  per iteration, putting the scan near 12.5 ms/tick in Sample B and around 25 ms at 58 players and 3000
  zombies. The relay grid reports about 9 zombies per 64-tile cell, so a radius query bounded by
  `max(20, viewDist)` would touch roughly 36 zombies instead of 1729. Reusing the existing grid is worth
  more than any threading change considered in this session.
- **Ownerless-zombie auth jitter.** `NetworkZombieManager.updateAuth` treats `owner == null` as an
  unconditional bypass of the 2 s hysteresis, so every unownable zombie is rescanned every tick.
  `auth.maxUpdateMs` grew 6.68 to 41.08 between samples, 6.1x, against an average that grew only 2.9x,
  which is the signature of load-time bursts. A short jittered rescan interval for ownerless zombies,
  seeded per zombie at creation, would flatten it.
