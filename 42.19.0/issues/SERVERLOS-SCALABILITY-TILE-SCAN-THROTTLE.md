# ServerLOS Tile Scan Scalability - suspend() Latency and LOS Thread Throughput

## Status
OPEN

## Priority
HIGH

## File
`42.19.0/src/zombie/network/ServerLOS.java`

## Problem Statement

`serverMapPreMaxMs` reached **150ms** in a 22-player session and will scale further as
player count increases. This metric measures how long the main thread blocks inside
`ServerMap.preupdate()` waiting for the LOS thread to yield before cell finalization
can proceed. A second, related issue is that `LOSThread` throughput degrades linearly
with player count, causing per-player tile LOS to become increasingly stale at scale.

Telemetry evidence:
```
22 players: serverMapPreMaxMs=150ms, playerLOSObjects=10485
18 players: serverMapPreMaxMs=78ms,  playerLOSObjects=5800
 8 players: serverMapPreMaxMs=0ms
```

---

## Root Cause Analysis

### Why suspend() blocks

`ServerMap.preupdate()` calls `ServerLOS.instance.suspend()` before finalizing loaded
cells into the active grid, and `processDeferredUnloads()` calls it before each cell
teardown. Both operations mutate `IsoChunk.chunks[][]`, which the LOS thread reads
directly during `calcLOS()`. The barrier is mandatory - removing it would cause
NPEs or torn visibility data.

`suspend()` spins until `this.suspended == true`. The LOS thread only sets
`suspended = true` inside `shouldWait()`, which is only reached AFTER the current
player's `calcLOS()` call returns. `mapLoading` is checked between players, not inside
`calcLOS`:

```java
// ServerLOS.java LOSThread.runInner() lines 283-295
for (int i = 0; i < ServerLOS.this.playersLos.size(); i++) {
    ServerLOS.PlayerData data = ServerLOS.this.playersLos.get(i);
    if (data.status == ServerLOS.UpdateStatus.WaitingInLOS) {
        data.status = ServerLOS.UpdateStatus.BusyInLOS;
        this.calcLOS(data);           // no yield point inside - atomic 73728-op scan
        data.status = ServerLOS.UpdateStatus.ReadyInLOS;
    }
    if (ServerLOS.this.mapLoading) {
        break;                         // only checked after a full player scan finishes
    }
}
```

### calcLOS cost

`calcLOS()` iterates 96 x 96 x sizeZ grid squares per player (lines 345-358). Each
iteration calls `sq.CalcVisibility()`, `sq.isCouldSee()`, and `sq.checkRoomSeen()`.
At approximately 2 microseconds per grid square:

    96 x 96 x 8 (sizeZ) = 73,728 ops x 2µs = ~147ms per player scan

This matches the observed 150ms ceiling. This cost is independent of player count -
it is always one player's atomic scan that the main thread must wait out.

### Throughput degradation at scale

All N players' `calcLOS` calls run sequentially in `runInner()`. With N=100 players all
having moved in the current tick, the LOS thread needs ~N x 2ms = ~200ms per full cycle.
Each player's tile visibility grid becomes progressively more stale. The `mapLoading`
check is still between players so suspend() wait stays bounded, but players see
increasingly outdated LOS data.

### What the tile LOS grid is used for

`data.visible[][][]` is consumed only by:
1. `isCouldSee(player, sq)` - called by `NetworkZombiePacker` to determine which
   zombies to send to each player (bandwidth optimization)
2. `updateLOS(player)` on the main thread - used for object-level visibility decisions

It is NOT used by zombie simulation, animal AI, spawn decisions, or pathfinding. The
staleness tolerance is high: a 300-500ms stale tile grid causes only minor latency
variation in zombie network sending. No simulation correctness is affected.

---

## Existing Throttles (Context)

These are already in place and must not be removed or broken:

| Throttle | Location | Value |
|---|---|---|
| Object LOS interval | `getObjectLosIntervalNanos()` | 200-750ms, pressure-scaled |
| Candidate index TTL | `getCandidateIndexIntervalNanos()` | 100-400ms, pressure-scaled |
| Tile skip if no movement | `calcLOS()` line 311-313 | position equality check |
| LOS frame gate | `updateLosThisFrame` + `LOS_TICK` | `OnceEvery(1.0F)` |

The new tile LOS interval (Layer 2 below) must integrate with, not replace, these.

---

## Implementation Plan

### Layer 1 - Break calcLOS Atomicity (fixes suspend() latency)

**Goal:** reduce `serverMapPreMaxMs` from ~150ms to ~2ms regardless of player count.

**Mechanism:** introduce a second visibility buffer per player (`pendingVisible[][][]`).
Write the scan output into `pendingVisible` instead of `data.visible` directly. After
each row of X (96 iterations completed), check `mapLoading`. If true, abandon the scan
without publishing - `data.visible` retains its previous coherent state. Only swap
`pendingVisible -> visible` when the scan completes cleanly. No torn reads are possible
because `data.visible` is only replaced atomically at scan completion.

#### 1a. Add `pendingVisible` buffer to `PlayerData`

In the `PlayerData` inner class (lines 383-395), add:

```java
public boolean[][][] pendingVisible = new boolean[96][96][LosUtil.sizeZ];
```

This is a second buffer of identical dimensions to the existing `visible` field.

#### 1b. Rewrite `calcLOS()` to use abandon-on-interrupt pattern

Replace the scan body inside `calcLOS()` (lines 322-362) as follows:

```java
private void calcLOS(ServerLOS.PlayerData data) {
    boolean skip = data.px == PZMath.fastfloor(data.player.getX())
        && data.py == PZMath.fastfloor(data.player.getY())
        && data.pz == PZMath.fastfloor(data.player.getZ());
    data.px = PZMath.fastfloor(data.player.getX());
    data.py = PZMath.fastfloor(data.player.getY());
    data.pz = PZMath.fastfloor(data.player.getZ());
    data.player.initLightInfo2();
    if (skip) {
        return;
    }

    int playerIndex = 0;
    LosUtil.PerPlayerData ppd = LosUtil.cachedresults[0];
    ppd.checkSize();

    for (int x = 0; x < LosUtil.sizeX; x++) {
        for (int y = 0; y < LosUtil.sizeY; y++) {
            for (int z = 0; z < LosUtil.sizeZ; z++) {
                ppd.cachedresults[x][y][z] = 0;
            }
        }
    }

    try {
        IsoPlayer.players[0] = data.player;
        int playerX = data.px;
        int playerY = data.py;
        int playerZ = data.pz;
        int minX = playerX - 48;
        int maxX = minX + 96;
        int minY = playerY - 48;
        int maxY = minY + 96;
        int minZ = playerZ - LosUtil.sizeZ / 2;
        int maxZ = minZ + LosUtil.sizeZ;
        IsoGameCharacter isoGameCharacter = data.player;
        VisibilityData visibilityData = isoGameCharacter.calculateVisibilityData();

        for (int x = minX; x < maxX; x++) {
            // Yield point: abandon this scan if the main thread is waiting.
            // data.visible retains its previous coherent state.
            if (ServerLOS.this.mapLoading) {
                return;
            }
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    IsoGridSquare sq = ServerMap.instance.getGridSquare(x, y, z);
                    if (sq != null) {
                        sq.CalcVisibility(0, isoGameCharacter, visibilityData);
                        data.pendingVisible[x - minX][y - minY][z - minZ] = sq.isCouldSee(0);
                        sq.checkRoomSeen(0);
                    } else {
                        data.pendingVisible[x - minX][y - minY][z - minZ] = false;
                    }
                }
            }
        }
        // Scan completed without interruption: publish the result atomically.
        boolean[][][] tmp = data.visible;
        data.visible = data.pendingVisible;
        data.pendingVisible = tmp;
        data.lastTileLosNanos = System.nanoTime();
    } finally {
        IsoPlayer.players[0] = null;
    }
}
```

Key points:
- The `return` on `mapLoading` inside the X loop abandons the scan without publishing.
  `data.visible` is untouched, so `isCouldSee()` keeps returning the previous snapshot.
- The buffer swap at the end is a pointer swap, not a copy - O(1).
- `data.lastTileLosNanos` is only updated on a completed scan. A scan interrupted at
  row X=30 does not advance the timestamp, ensuring the scan is retried next time.

**Expected result:** `serverMapPreMaxMs` drops from ~150ms to the time for one X-row
of the scan (~96 x sizeZ x 2µs = ~1.5ms), independent of player count.

---

### Layer 2 - Per-Player Tile LOS Interval (fixes LOS thread throughput)

**Goal:** bound LOS thread CPU time under high player counts by rate-limiting how often
each player's full tile scan runs, using the same pressure-aware pattern already in place
for object LOS.

#### 2a. Add constants for tile LOS interval

After the existing `OBJECT_LOS_INTERVAL_*` constants (lines 30-35), add:

```java
// Tile LOS (96x96 grid scan) is throttled separately from object LOS.
// Base interval is shorter because tile LOS drives zombie network visibility directly.
private static final long TILE_LOS_INTERVAL_BASE_NANOS = Math.max(50L,
        Math.min(500L, Long.getLong("apocbr.los.tileIntervalMs", 100L))) * 1_000_000L;
private static final long TILE_LOS_INTERVAL_MAX_NANOS = Math.max(TILE_LOS_INTERVAL_BASE_NANOS,
        Math.min(1000L, Long.getLong("apocbr.los.tileIntervalMaxMs", 400L))) * 1_000_000L;
```

Defaults: base=100ms, max=400ms. Configurable via JVM system properties.

#### 2b. Add `lastTileLosNanos` to `PlayerData`

In the `PlayerData` class alongside `lastObjectLosNanos` (line 389):

```java
public long lastTileLosNanos;
```

Note: `lastTileLosNanos` is already set by the rewritten `calcLOS()` in Layer 1 (on
completed scans only). Declare it here; Layer 1's assignment is the authoritative write.

#### 2c. Add `getTileLosIntervalNanos()` method

Add after `getObjectLosIntervalNanos()` (after line 180):

```java
private long getTileLosIntervalNanos(IsoPlayer player) {
    IsoCell cell = player == null ? null : player.getCell();
    int objectCount = cell == null ? 0 : cell.getObjectList().size();
    int loadedCellCount = ServerMap.instance == null ? 0 : ServerMap.instance.loadedCells.size();
    long pressureNanos = 0L;
    pressureNanos += (long)Math.max(0, objectCount - 250) / 250L * 20_000_000L;
    pressureNanos += (long)Math.max(0, loadedCellCount - 8) * 4_000_000L;
    return Math.max(
            TILE_LOS_INTERVAL_BASE_NANOS,
            Math.min(TILE_LOS_INTERVAL_MAX_NANOS, TILE_LOS_INTERVAL_BASE_NANOS + pressureNanos)
    );
}
```

Pressure formula mirrors `getObjectLosIntervalNanos()` with slightly smaller step
coefficients since tile LOS is more latency-sensitive than object LOS.

#### 2d. Gate `calcLOS()` on the tile interval

In `runInner()` (lines 283-295), before dispatching `calcLOS`, add the interval check:

```java
for (int i = 0; i < ServerLOS.this.playersLos.size(); i++) {
    ServerLOS.PlayerData data = ServerLOS.this.playersLos.get(i);
    if (data.status == ServerLOS.UpdateStatus.WaitingInLOS) {
        long nowNanos = System.nanoTime();
        long tileInterval = ServerLOS.this.getTileLosIntervalNanos(data.player);
        if (data.lastTileLosNanos != 0L && nowNanos - data.lastTileLosNanos < tileInterval) {
            // Interval not elapsed: skip tile scan this pass,
            // but keep WaitingInLOS so we retry on the next runInner cycle.
            if (ServerLOS.this.mapLoading) {
                break;
            }
            continue;
        }
        data.status = ServerLOS.UpdateStatus.BusyInLOS;
        ServerLOS.this.noise("BusyInLOS playerID=" + data.player.onlineId);
        this.calcLOS(data);
        data.status = ServerLOS.UpdateStatus.ReadyInLOS;
    }
    if (ServerLOS.this.mapLoading) {
        break;
    }
}
```

Important: a player whose interval has not elapsed remains `WaitingInLOS` so that
`shouldWait()` does not send the LOS thread to sleep prematurely. The thread continues
iterating the player list and will pick up the next due player.

---

## Telemetry Additions

Add the following to `ApocBRServerTelemetry` (or the existing playerLOS telemetry block)
to make both layers observable:

- `playerLOSAbandoned` - count of `calcLOS` calls interrupted by `mapLoading` per interval
- `playerLOSTileSkipped` - count of players skipped due to tile interval throttle per interval
- `playerLOSTileAvgMs` / `playerLOSTileMaxMs` - timing of completed (non-abandoned) tile scans

These will confirm whether Layer 1 interruptions are occurring at an expected rate and
whether Layer 2 is reducing throughput pressure as designed.

---

## Invariants That Must Not Be Broken

1. `data.visible[][][]` must always be a coherent snapshot - never partially written.
   The buffer swap guarantees this.
2. `IsoPlayer.players[0]` must be reset to `null` in a finally block even on abandonment.
   The existing `finally` block handles this.
3. `calcLOS` runs only on the `LOSThread` - never on the main thread. Do not call it
   from `updateLOS()` or any main-thread path.
4. `suspend()` must still guarantee that `data.visible` is not being written when it
   returns. With the abandon pattern, an interrupted `calcLOS` leaves `data.visible`
   untouched, so this invariant holds.
5. The interval gate in `runInner()` must not change a player's status from
   `WaitingInLOS` to `ReadyInLOS` without actually running `calcLOS`. The `continue`
   keeps the player in `WaitingInLOS` and the LOS thread will retry next cycle.

---

## Verification

After implementing, confirm via telemetry:

| Metric | Before | Expected After |
|---|---|---|
| `serverMapPreMaxMs` | ~150ms at 22 players | <5ms at any player count |
| `playerLOSAbandoned` | (new) | non-zero under load, <20% of total scans |
| `playerLOSTileSkipped` | (new) | increases with player count and load pressure |
| `playerLOSComputeMaxMs` | 0ms (already <1ms) | unchanged |
| `avgMs` (world tick) | 57-70ms at 18-22 players | measurably lower pre contribution |

`serverMapPreMaxMs` is the primary regression detector. If it remains above 10ms after
this fix, the abandon pattern is not firing - check that `mapLoading` is `volatile`
(confirmed in prior fix) and that the buffer swap is not accidentally skipped.

---

## Files to Modify

1. `42.19.0/src/zombie/network/ServerLOS.java`
   - Add `TILE_LOS_INTERVAL_BASE_NANOS`, `TILE_LOS_INTERVAL_MAX_NANOS` constants
   - Add `getTileLosIntervalNanos()` method
   - Add `pendingVisible[][][]` and `lastTileLosNanos` to `PlayerData`
   - Rewrite `calcLOS()` body (abandon-on-interrupt + buffer swap)
   - Update `runInner()` to add interval gate before dispatching `calcLOS`

2. `42.19.0/src/zombie/ApocBRServerTelemetry.java` (if telemetry additions are wanted)
   - Add `playerLOSAbandoned`, `playerLOSTileSkipped`, tile timing fields

No changes to `ServerMap.java`, `ServerChunkLoader.java`, or any other file.
The `suspend()/resume()` contract in `ServerMap` remains unchanged.
