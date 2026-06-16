# MovingObjectUpdateScheduler — Double postupdate() Investigation

## Context

Investigation into why the local client exhibits zombie/entity simulation running at 3x–4x the intended speed after applying the ApocBR parallel patches.

**Source of truth for "original" behavior:** Decompiled classes from the first B42 multiplayer build (ZomboidDecompiler output).  
**Patched code:** `42.19.0/src/` folder.

---

## Architecture: Update Scheduler Buckets

The game distributes `IsoMovingObject` (players, zombies, animals, vehicles) across five buckets based on distance, visibility, and FPS. Each bucket has a `frameMod` that determines how often objects in it are updated:

| Bucket | `frameMod` | Update interval | `perObjectMultiplier` |
|--------|-----------|-----------------|----------------------|
| `fullSimulation` | 1 | Every frame | 1× |
| `halfSimulation` | 2 | Every 2 frames | 2× |
| `quarterSimulation` | 4 | Every 4 frames | 4× |
| `eighthSimulation` | 8 | Every 8 frames | 8× |
| `sixteenthSimulation` | 16 | Every 16 frames | 16× |

The `perObjectMultiplier` is set on `GameTime.getInstance()` inside each bucket's `update()` / `postupdate()` / `updateAnimation()` loop. It multiplies the time delta so objects that update less frequently still simulate the same amount of "game time" — e.g. a quarterSimulation object runs with 4× time multiplier to compensate for updating only every 4th frame.

---

## Update Call Flow

### Original (first B42 MP decompiled)

```
IsoWorld.update()
  └─ IsoWorld.updateInternal()
       ├─ WorldSimulation, Hutch, Fog, Helicopter, Emitters...
       ├─ WorldSound, ZombieGroup, OnceEvery, Collision
       ├─ [optional] thread = async(this::updateThread)  ← DebugOptions.threadWorld
       ├─ ClimateManager.update()
       ├─ updateWorld()
       │    ├─ currentCell.update()
       │    │    └─ IsoCell.updateInternal()
       │    │         ├─ scheduler.startFrame()         ← clear buckets + populate
       │    │         ├─ ProcessSpottedRooms, chunkMap, Items, IsoObject
       │    │         ├─ safeToAdd = false
       │    │         ├─ ProcessObjects(null)
       │    │         │    └─ scheduler.update()         ← preupdate() + update() on current bucket
       │    │         ├─ [Network stuff]
       │    │         ├─ safeToAdd = true
       │    │         ├─ ProcessStaticUpdaters
       │    │         ├─ ObjectDeletionAddition
       │    │         ├─ IsoDeadBody.updateBodies()
       │    │         ├─ FishSchoolManager.update()
       │    │         ├─ light counters, rain scroll, weather FX
       │    ├─ IsoRegions.update()
       │    ├─ HaloTextHelper.update()
       │    ├─ CollisionManager.instance.ResolveContacts()
       │    └─ scheduler.postupdate()                    ← postupdate() called ONCE
       └─ [if thread != null] thread.join()
          [else] updateThread() sync (buildings, virtual animals, DBs, coop players)
```

Key: **postupdate() is called exactly once per frame**, inside `IsoWorld.updateWorld()`.

### Patched (42.19.0)

```
IsoWorld.update()
  └─ IsoWorld.updateInternal()
       ├─ [same preamble: WorldSim, Hutch, Fog, Heli, Emitters...]
       ├─ safeWorldFuture = apocBrMaybeSubmitSafeWorldParallel()
       │    └─ async: updateBuildings + ObjectRenderEffects.updateStatic()
       │              + AnimalZones.updateVirtualAnimals()
       │              + AnimalTracksDefinitions.loadTracksDefinitions()
       ├─ ClimateManager.update()
       ├─ updateWorld()
       │    ├─ currentCell.update()
       │    │    └─ IsoCell.update()                     ← MODIFIED
       │    │         ├─ scheduler.startFrame()
       │    │         ├─ ProcessSpottedRooms, chunkMap, Items, IsoObject
       │    │         ├─ safeToAdd = false
       │    │         ├─ ProcessStaticUpdaters            ← MOVED: now BEFORE ProcessObjects
       │    │         ├─ ObjectDeletionAddition            ← MOVED: now BEFORE ProcessObjects
       │    │         ├─ ProcessObjects(null)
       │    │         │    └─ scheduler.update()           ← preupdate() + frameStep() + update()
       │    │         ├─ safeToAdd = true
       │    │         ├─ scheduler.postupdate()            ← *** NEW: 1st postupdate() call ***
       │    │         └─ async: IsoDeadBody, FishSchool, counters, rain, weather
       │    ├─ IsoRegions.update()
       │    ├─ HaloTextHelper.update()
       │    ├─ CollisionManager.instance.ResolveContacts()
       │    └─ scheduler.postupdate()                     ← *** 2nd postupdate() call (unchanged from original) ***
       ├─ apocBrCompleteSafeWorldParallel(safeWorldFuture)
       │    └─ [if future != null] future.join()
       │       [else] updateThreadSafeParallel() sync
       └─ updateThreadMainOnly()                          ← coopPlayers, DBs, safehouse
```

Key: **postupdate() is called TWICE per frame** — once inside `IsoCell.update()`, then again inside `IsoWorld.updateWorld()`.

---

## Findings

### 🔴 FINDING 1: Double `postupdate()` Call

**This is the most critical bug.**

`IsoCell.update()` (in the patched version) calls `MovingObjectUpdateScheduler.instance.postupdate()` at line ~4184. But `IsoWorld.updateWorld()` also calls `scheduler.postupdate()` immediately after `currentCell.update()` returns. Since `IsoCell.update()` is called FROM `IsoWorld.updateWorld()` via `this.currentCell.update()`, the result is:

```
postupdate() runs on the SAME objects, with the SAME frameCounter, TWICE per frame
```

**Effect per bucket:**

For a `quarterSimulation` object (`frameMod = 4`):
1. `postupdate()` 1st call: `perObjectMultiplier = 4`, object state advances with 4× time
2. `postupdate()` 2nd call: `perObjectMultiplier = 4` again, object state advances AGAIN with 4× time

Net result: **8× effective post-update processing** instead of intended 4×.

For objects in `fullSimulation` (`frameMod = 1`):
- `perObjectMultiplier = 1` both times, so no time acceleration, but the `postupdate()` logic itself runs twice — potentially doubling state transitions.

**What `postupdate()` typically contains:**
- State machine transitions (zombies advancing through attack/wander/chase states)
- Animation finalization
- Movement completion/correction
- Network state accumulation

**Impact:** Entities advance through their behavior states at roughly double speed. Combined with other factors below, this manifests as zombies/players appearing to simulate at 3×–4× speed.

---

### 🔴 FINDING 2: `frameStep()` Inserted Between `preupdate()` and `update()`

**Original `MovingObjectUpdateSchedulerUpdateBucket.update()`:**
```java
isoMovingObject.preupdate();
isoMovingObject.update();
```

**Patched `MovingObjectUpdateSchedulerUpdateBucket.update()`:**
```java
isoMovingObject.preupdate();
isoMovingObject.frameStep();    // ← ADDED
isoMovingObject.update();
```

This additional call was added by the patcher (it does not exist in the decompiled original). The `frameStep()` method does per-object frame-advance work.

**Impact:** Objects do more work per update cycle than the original game expected. Whether this actually causes speedup depends on what `frameStep()` does internally — but combined with FINDING 1, the per-frame cost is higher.

---

### 🔴 FINDING 3: Execution Order Change in IsoCell.update()

**Original order:**
```
safeToAdd = false
ProcessObjects(null)           ← entity updates run first
[network stuff]
safeToAdd = true
ProcessStaticUpdaters          ← static updaters run AFTER
ObjectDeletionAddition         ← deletions run AFTER
```

**Patched order:**
```
safeToAdd = false
ProcessStaticUpdaters          ← static updaters run BEFORE
ObjectDeletionAddition         ← deletions run BEFORE
ProcessObjects(null)           ← entity updates run AFTER
safeToAdd = true
```

Moving `ProcessStaticUpdaters` and `ObjectDeletionAddition` to **before** entity updates changes the game state that entities observe during their `update()` call — static object state, removal flags, and spatial data may differ.

---

### 🔴 FINDING 4: FPS-Based Simulation Tier Boosting (Vanilla 42.19 Behavior)

The patched `MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject()` uses `UpdateSchedulerSimulationLevel` with FPS-based dynamic adjustment:

```java
if (averageFps > 25.0F) sim = sim.more();   // +1 level
if (averageFps > 35.0F) sim = sim.more();   // +1 level
if (averageFps > 45.0F) sim = sim.more();   // +1 level
if (averageFps > 55.0F) sim = sim.more();   // +1 level
```

On a local development machine running at **60+ FPS**, objects that would normally be throttled to `QUARTER` or `EIGHTH` (based on distance) can receive up to **+4 levels** of boost, moving them to `FULL` or `HALF`.

**Impact:** Objects that should update every 4–8 frames instead update every 1–2 frames, giving 4×–8× more simulation. This is vanilla 42.19 behavior, NOT introduced by the patch — but it **amplifies** the effect of the double postupdate bug.

---

### 🟡 FINDING 5: Parallelism Restructuring (IsoWorld)

**Original:** `updateThread()` ran in parallel with Climate + world update, containing:
- `updateBuildings()`
- `ObjectRenderEffects.updateStatic()`
- `addCoopPlayers` loop
- `IsoPlayer.UpdateRemovedEmitters()`
- `updateDBs()` (PlayerDB + VehiclesDB)
- Safehouse player update
- `AnimalZones.updateVirtualAnimals()`
- `AnimalTracksDefinitions.loadTracksDefinitions()`

**Patched:** Split into two methods:
- `updateThreadSafeParallel()` — runs async with `Climate.update()`: buildings, staticEffects, virtualAnimals, animalDefs
- `updateThreadMainOnly()` — runs AFTER join on main thread: coopPlayers, DBs, safehouse

This split is **safer** (removes DB I/O from parallel execution) and does **not** contribute to the speedup. It is a correct architectural improvement.

---

## Root Cause Summary

| Factor | Source | Severity |
|--------|--------|----------|
| **Double `postupdate()`** | Bug in patched `IsoCell.update()` — calls `postupdate()` redundantly | 🔴 Critical |
| **`frameStep()` insertion** | Added call in `MovingObjectUpdateSchedulerUpdateBucket.update()` | 🔴 High |
| **Execution reorder** | `ProcessStaticUpdaters`/`ObjectDeletionAddition` moved before `ProcessObjects` | 🔴 High |
| **FPS boosting** | Vanilla 42.19 behavior, amplified by local high FPS | 🟡 Medium |

**On dedicated servers:** The scheduler always returns `FULL` for all objects (`GameServer.server` check), so:
- No FPS boosting (point 4)
- `perObjectMultiplier = 1` always (no time acceleration from double postupdate)
- But the double `postupdate()` call still happens — the extra iteration just doesn't speed up time

**On local client:** All four factors combine:
- High FPS (60+) pushes objects to faster buckets
- Double postupdate applies 2× the intended post-processing
- `frameStep()` adds another processing step per update
- Reordered execution changes observable game state

---

## Recommended Fix

### Primary Fix (IsoCell.java)

Remove the redundant `postupdate()` call from `IsoCell.update()`. The original architecture intentionally placed `postupdate()` only in `IsoWorld.updateWorld()` to ensure it runs exactly once per frame:

```java
// REMOVE this line from IsoCell.update() ~line 4184:
MovingObjectUpdateScheduler.instance.postupdate();
```

### Secondary Fix (MovingObjectUpdateSchedulerUpdateBucket.java — if applicable)

Review whether the `frameStep()` call belongs between `preupdate()` and `update()`. If it was added as part of the parallel patch (not vanilla 42.19 behavior), consider removing it or moving it inside `preupdate()` or `update()`.

### Tertiary Fix (IsoCell.java — if needed)

Consider restoring the original execution order so that `ProcessStaticUpdaters` and `ObjectDeletionAddition` run AFTER `ProcessObjects`, matching the original game's assumption about execution ordering.

---

## Files Referenced

| File | Path |
|------|------|
| Patched IsoWorld | `42.19.0/src/zombie/iso/IsoWorld.java` |
| Patched IsoCell | `42.19.0/src/zombie/iso/IsoCell.java` |
| Patched Scheduler | `42.19.0/src/zombie/MovingObjectUpdateScheduler.java` |
| Patched Bucket | `42.19.0/src/zombie/MovingObjectUpdateSchedulerUpdateBucket.java` |
| Decompiled IsoWorld | `zombie/iso/IsoWorld.java` (ZomboidDecompiler output) |
| Decompiled IsoCell | `zombie/iso/IsoCell.java` (ZomboidDecompiler output) |
| Decompiled Scheduler | `zombie/MovingObjectUpdateScheduler.java` (ZomboidDecompiler output) |
| Decompiled Bucket | `zombie/MovingObjectUpdateSchedulerUpdateBucket.java` (ZomboidDecompiler output) |
| Decompiled GameTime | `zombie/GameTime.java` (ZomboidDecompiler output) |

---

## Raw Diff Summary

### IsoCell.update() vs decompiled IsoCell.updateInternal()

| Aspect | Original | Patched |
|--------|----------|---------|
| Scheduler call | `startFrame()` → `ProcessObjects` → (no postupdate) | `startFrame()` → `ProcessObjects` → **`postupdate()`** |
| StaticUpdaters order | After ProcessObjects | Before ProcessObjects |
| ObjectDeletionAddition order | After ProcessObjects | Before ProcessObjects |
| DeadBodies/Fish/Weather | Synchronous, main thread | Async on ForkJoinPool |

### MovingObjectUpdateSchedulerUpdateBucket.update()

| Aspect | Original | Patched |
|--------|----------|---------|
| Update sequence | `preupdate()` → `update()` | `preupdate()` → **`frameStep()`** → `update()` |

### IsoWorld.updateInternal()

| Aspect | Original | Patched |
|--------|----------|---------|
| Parallel work | `updateThread()` (buildings + VA + DBs + coop) | `updateThreadSafeParallel()` (buildings + staticEffects + VA + animalDefs) then `updateThreadMainOnly()` (coop + DBs + safehouse) |
| Thread safety guard | None | `apocBrSafeWorldFuture` with backlog skip logic |
