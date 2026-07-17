# Patched Source Diff Audit - src/ vs decompiled/ (Build 42.19.0)

## Status
OPEN

## Priority
HIGH (contains a likely root cause for the "building interior stays permanently dark / does not un-hide" report, and a non-functional anti-cheat check)

## Scope

Full `git diff --no-index` comparison of every file in `42.19.0/src` against its
counterpart in `42.19.0/decompiled`, run against the production source snapshot
imported into this workspace. 54 source files total:

- **3 files identical** to decompiled (`IsoGridSquare.java`, `IsoZombie.java`, `ItemContainer.java`)
- **3 new files** with no decompiled counterpart (`ApocBRServerTelemetry.java`, `Lua/ApocBRMainThreadLuaQueue.java`, `Lua/AsyncLuaManager.java`)
- **48 files modified**, ranging from single-line null guards to large structural rewrites

This document does not implement any fixes. It is a findings report to plan work from.

---

## Critical Findings

### 1. Chunk-unload path no longer suspends the LOS thread - likely root cause of "building never un-hides / stays black"

**Files:** `zombie/network/ServerMap.java`, `zombie/iso/IsoChunk.java`

In vanilla, `ServerMap.postupdate()` unloads a cell atomically inside a single
`ServerLOS.instance.suspend()` / `resume()` pair:

```java
// decompiled/zombie/network/ServerMap.java
if (!pathfindPaused) {
    ServerLOS.instance.suspend();
    pathfindPaused = true;
}
this.cellMap[y * this.width + x].Unload();
...
} finally {
    if (pathfindPaused) ServerLOS.instance.resume();
}
```

The patch replaces this with a **throttled, multi-tick unload** to avoid frame
hitches: `ServerCell.Unload(int unloadSlicesPerTick)` now processes at most
`UNLOAD_SQUARES_PER_SLICE` (254) grid squares per call via
`IsoChunk.processRemoveFromWorldSquares()`, resuming from a saved cursor
(`unloadChunkX` / `unloadChunkY`) on the next tick until the whole cell is torn
down. This is driven by the new `processDeferredUnloads()` method, called from
`postupdate()`.

**The new `processDeferredUnloads()` path never calls `ServerLOS.instance.suspend()` /
`resume()` around the per-tick partial unload.** Only the cell-*load* finalize
path (`Load2()`, inside `preupdate()`) still has the suspend/resume guard.

Effect: while a cell is being incrementally unloaded, `IsoChunk.removeSquareFromWorld()`
runs concurrently with the `LOSThread` in `ServerLOS`, which calls
`sq.CalcVisibility()`, `sq.isCouldSee()`, `sq.checkRoomSeen()` for the *same*
grid squares on a different thread (`ServerLOS.java` `calcLOS()`, still an atomic
96x96xZ scan with no yield point). `removeSquareFromWorld()` does, among other
things:

```java
if (sq.getRoom() != null) sq.getRoom().removeSquare(sq);
...
sq.softClear();
sq.chunk = null;
```

A square whose `room.removeSquare(sq)` already ran, or whose `chunk` is nulled,
observed mid-scan by `calcLOS()`/`CalcVisibility()` produces incorrect
`isCouldSee()` / `room.def.explored` state for that square. Since
`room.def.explored` is exactly what gates whether a room renders lit or fully
black (`IsoGridSquare.CalcVisibility()`, `targetDarkMulti(0.0F)` branch when
`!lighting.bSeen()`), and `ServerLOS.calcLOS()` has a `skip`-if-player-hasn't-moved-tile
optimization, a bad `explored=false`/`isCouldSee=false` result latched during
this race will **not self-correct** until the player changes grid tile - matching
the reported symptom that breaking a wall in place does not fix the darkness.

Custom maps with larger/denser buildings near cell boundaries make this more
likely to reproduce, because more of a building's chunks fall inside the
unload/reload churn radius under the now much more aggressive unload throughput
(`UNLOAD_SLICES_*`, `UNLOAD_CELLS_*`, see Finding 3).

**Recommended fix direction:** wrap each `processDeferredUnloads()` unload
attempt (or at minimum each `mapCell.Unload(unloadSlicesPerTick)` call) in
`ServerLOS.instance.suspend()` / `resume()`, same as the load-finalize path.
This reintroduces a bounded stall on the LOS thread per unload slice, so the
slice size may need tuning to keep `serverMapPreMaxMs`-style budgets low (see
`SERVERLOS-SCALABILITY-TILE-SCAN-THROTTLE.md`) - implementing that issue's
Layer 1 (abandon-on-interrupt `calcLOS`) first would make the added suspend
calls cheap.

---

### 2. Godmode healing anti-cheat check is non-functional (`==` instead of `.equals()`)

**File:** `zombie/characters/BodyDamage/BodyDamage.java`

```java
if (player.getAccessLevel() != null) {
    isAdmin = player.getAccessLevel() == "admin";   // reference comparison
}
```

`getAccessLevel()` returns a `String` built from role/DB data, not the literal
`"admin"`. Comparing with `==` compares object identity, not content, so
`isAdmin` will be `false` for essentially all real admin accounts unless the
JVM happens to have interned an identical String instance. The intended
behavior ("only admins can heal via godmode") is very likely **not
enforced**, silently defeating this specific exploit patch. This is unrelated
to `godmode-exploit-patch.md` / `godmode-authority-analysis.md` /
`godmode-server-side-control-analysis.md`, which do not mention this check.

**Fix:** `"admin".equals(player.getAccessLevel())`.

---

### 3. `ServerLOS.suspend()` timeout weakens the load-finalize barrier

**File:** `zombie/network/ServerLOS.java`

Vanilla `suspend()` spins unconditionally until the LOS thread reports
`suspended`. The patch adds a `SUSPEND_WAIT_TIMEOUT_MS = 500L` early-exit:

```java
if (System.currentTimeMillis() - start >= SUSPEND_WAIT_TIMEOUT_MS) {
    DebugType.General.println("ServerLOS.suspend timeout after " + SUSPEND_WAIT_TIMEOUT_MS + "ms");
    break;
}
```

`calcLOS()` is a single atomic ~96x96xZ scan (documented at ~147ms/player in
`SERVERLOS-SCALABILITY-TILE-SCAN-THROTTLE.md` at 22 players). Under load, or with
several players queued, this timeout can fire while the LOS thread is still
mid-scan, letting `ServerMap.preupdate()`'s `Load2()` finalize path mutate
`IsoChunk.chunks[][]` concurrently with `calcLOS()` reading it - the exact
"torn visibility data" scenario the code comment above `suspend()`'s call site
warns must not happen. This compounds Finding 1 and should be fixed by the same
underlying change (make `calcLOS()` interruptible/fast per
`SERVERLOS-SCALABILITY-TILE-SCAN-THROTTLE.md`, then this timeout becomes a
true safety net instead of a routinely-hit escape hatch).

---

### 4. Unbounded per-frame async task spawn with no single-flight guard

**File:** `zombie/iso/objects/IsoZombieGiblets.java`

```java
@Override
public void update() {
    CompletableFuture.runAsync(() -> {
        try { this.updateAsync(); }
        catch (Throwable t) { ExceptionLogger.logException(t); }
    }, PZForkJoinPool.commonPool());
}
```

Every `IsoZombieGiblets` instance queues a **new** ForkJoinPool task every
single frame, with no in-flight guard. Contrast with the (disabled) `IsoAnimal`
attempt at the same pattern, which used an `AtomicBoolean asyncUpdateInFlight`
compare-and-set to guarantee single-flight execution per object. During heavy
combat (many giblets active at once), this can queue large numbers of
overlapping tasks per object, each mutating shared object state
(`getZ()`, `getCurrentSquare()`, sprite/physics fields) without synchronization.
Low severity (giblets are cosmetic/short-lived) but worth hardening with the
same single-flight pattern, or reverting to synchronous update.

---

### 5. Silent exception swallowing in `setForwardIsoDirection`

**File:** `zombie/characters/IsoGameCharacter.java`

```java
if (PZMath.equal(forwardDirectionLength, 0.0F)) {
    return;
    // throw new IllegalStateException("Forward Direction cannot be zero length vector.");
}
```

The original `throw` is commented out and replaced with a silent `return`.
This may be masking a legitimate upstream bug (something computing a
zero-length forward vector) rather than fixing it. No comment explains why
this is safe. Recommend root-causing the zero-length-vector callers before
keeping this as a permanent behavior change; at minimum log when it happens.

---

### 6. Dead/unintegrated async-animal-update scaffolding

**Files:** `zombie/characters/animals/IsoAnimal.java`,
`zombie/Lua/AsyncLuaManager.java`, `zombie/Lua/ApocBRMainThreadLuaQueue.java`

`IsoAnimal` has an `AtomicBoolean asyncUpdateInFlight` field and a fully
implemented, single-flight `update()` override that dispatches to
`PZForkJoinPool.commonPool()` - **entirely commented out**. `AsyncLuaManager`
and `ApocBRMainThreadLuaQueue` (both new classes, wired into
`LuaManager.GlobalObject extends AsyncLuaManager`) implement a Lua-call queue
so that async worker threads can defer Lua calls back to the main thread
(Kahlua is not thread-safe). Grepping the whole `src/` tree,
**`ApocBRMainThreadLuaQueue.drain()` is never called from anywhere** (e.g. not
from `IsoWorld.updateThread()` or `IngameState.UpdateStuff()`), so even if the
`IsoAnimal.update()` override were re-enabled, any Lua callback queued from an
animal's async update would never execute.

This is clearly in-progress/paused work (matches the thread-safety comments
sprinkled through `IsoAnimal.java`: `localVehicle4Test` snapshotting,
`replaceSpottedList()`, snapshotted iteration in `alertOtherAnimals()`,
`reattachBackToMom()`, etc. - all preparing `IsoAnimal` to be safe to update off
the main thread). It is currently harmless dead code, but should either be
finished (re-enable `update()` override + wire `drain()` into the main loop) or
removed to avoid confusion.

---

### 7. `IsoAnimal.updateInternal()`: `updateEmitter()` call removed

**File:** `zombie/characters/animals/IsoAnimal.java`

```java
 this.updateStress();
 this.updateLured();
-this.updateEmitter();
 this.tryThump(null);
```

No comment explains the removal. Needs verification against `IsoAnimal`'s
sound-emitter lifecycle (does something else now call `updateEmitter()`, or is
animal ambient sound emission silently disabled?). Flagging for confirmation,
not asserting a bug.

---

## Per-Subsystem Summary

| Subsystem | Files | Nature of changes |
|---|---|---|
| **Async chunk save/unload + telemetry** | `ServerMap.java`, `IsoChunk.java`, `ServerChunkLoader.java`, `PlayerDownloadServer.java`, `WorldReuserThread.java` | Incremental/throttled cell unload (Finding 1), per-call CRC32 to fix a torn-write race, deferred-save awareness before serving chunk downloads to clients, telemetry hooks throughout |
| **Server-side LOS** | `ServerLOS.java` | Object-LOS interval throttling, spatial candidate index for zombie-packing bandwidth, `suspend()` timeout (Finding 3). Tile-scan atomicity fix is designed but **not yet implemented** - see `SERVERLOS-SCALABILITY-TILE-SCAN-THROTTLE.md` |
| **IsoCell / IsoWorld frame loop** | `IsoCell.java`, `IsoWorld.java` | `HashSet` → `ConcurrentHashMap.newKeySet()` for object/add/remove lists, `processItemsLock` for item-processing collections, snapshot-before-iterate patterns everywhere, exception isolation (`try/catch Throwable`) around previously unguarded sub-updates in `updateThread()`, extensive per-section telemetry |
| **Animals** | `IsoAnimal.java`, `VirtualAnimal.java`, `VirtualAnimalState.java`, `AnimalZones.java`, `AnimalChunk.java`, `AnimalPopulationManager.java` | Server-side simulation-level throttling (`apocBrGetServerSimulationLevel()`), frame-interval throttling for virtual (off-screen) animal state updates and track spawning, null-safety hardening, snapshot-before-iterate for thread-safety, dead async scaffolding (Finding 6) |
| **Vehicles** | `BaseVehicle.java`, `VehicleManager.java` | Server simulation-level throttling mirroring the animal changes; null/CME guards in `serverUpdate()` |
| **Pathfind native bridge** | `PathfindNative.java`, `ChunkUpdateTask.java` | Stale-chunk guard (`activeChunkLoadIds`) preventing a SIGSEGV in `libPZPathFind64.so` when a queued native task outlives its chunk's unload/reload cycle |
| **Pathfind / collision math** | `PolygonalMap2.java`, `LineClearCollideMain.java`, `PathFindBehavior2.java` | Infinite-loop guard in `supercover()` (iteration cap + `HashSet` dedup instead of `ArrayList.contains`), NaN/Infinite input rejection, `synchronized` on shared mutable scratch state now reachable from parallel animal updates, per-instance (not static) `pointOnPath` scratch object |
| **Zombie networking** | `NetworkZombiePacker.java`, `ZombiePopulationManager.java` | Tiered zombie-auth update cadence (urgent vs. round-robin budget) to avoid frame-count starvation under load, null-slot tolerance for concurrently-mutated zombie lists, detailed telemetry |
| **Entity/engine framework** | `EngineEntityManager.java`, `EntityBucket.java`, `EntityBucketManager.java`, `GameEntity.java`, `UsingPlayerUpdateSystem.java`, `FluidContainerUpdateSystem.java` | Null-safety and snapshot-before-iterate hardening for concurrent add/remove during async chunk retirement |
| **Inventory** | `CompressIdenticalItems.java` | Null guard preventing NPE → corrupted chunk save → vanished vehicles (documented, already applied per `APPLIED-FIXES.md`) |
| **Anti-cheat / networking** | `ExtraInfoPacket.java`, `BodyDamage.java` | Reject forced `ExtraInfoPacket` from non-admins (correct: uses `hasAdminPower()`); godmode-heal admin gate (Finding 2, buggy) |
| **Debug/threading toggles** | `DebugOptions.java` | Force-enables `Threading.Animation` and `Threading.Ambient` (not `Threading.World`, explicitly left off due to a documented Lua-thread-safety crash) |
| **Telemetry** | `ApocBRServerTelemetry.java` (new) | Central metrics sink referenced by nearly every other patched file; not diffed against decompiled since it has no vanilla counterpart |
| **Misc null-safety** | `WorldSoundManager.java`, `FishSchoolManager.java`, `IsoDoor.java`, `IsoPuddlesCompute.java` (added `synchronized`), `LuaManager.java` (cosmetic + `AsyncLuaManager` base class hookup) | Defensive null checks against concurrently-mutated collections, consistent with the broader async/concurrency theme of this patch set |

---

## Files Confirmed Identical to Decompiled

`IsoGridSquare.java`, `IsoZombie.java`, `ItemContainer.java` - no patch applied,
safe to treat as pure vanilla for behavioral purposes.

---

## Suggested Next Steps

1. **Fix Finding 1** (wrap `processDeferredUnloads()`'s unload calls with
   `ServerLOS.instance.suspend()/resume()`) - this is the most actionable lead
   for the reported black-building bug. Validate on a custom map with large
   buildings near the load-radius edge under multi-player load.
2. **Fix Finding 2** (`.equals()` instead of `==` in `BodyDamage.java`) - one-line
   fix, no design questions.
3. Decide on Finding 6: finish or remove the async-animal-update scaffolding.
4. Implement `SERVERLOS-SCALABILITY-TILE-SCAN-THROTTLE.md` Layer 1 to make
   Findings 1 and 3 cheaper to fix correctly (short LOS stalls instead of a
   500ms timeout risk).
5. Confirm Finding 7 (`updateEmitter()` removal) is intentional or restore it.
6. Consider a single-flight guard for Finding 4 (`IsoZombieGiblets.update()`).
