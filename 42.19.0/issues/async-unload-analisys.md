# Async Unload Risk Assessment

## Overview

This document assesses the practical risks of turning the cell unload mechanism async to offload the heavy square teardown operations from the main thread. The unload operation is currently the primary performance bottleneck, with max times spiking to 600-700ms per cell.

## Static Field Mutations Identified

### IsoChunk.removeFromWorld() (Lines 3151-3266)

| Static Field | Type | Mutation Location | Risk Level |
|--------------|------|-------------------|------------|
| `IsoChunk.loadGridSquare` | `ConcurrentLinkedQueue<IsoChunk>` | Line 3152: `loadGridSquare.remove(this)` | LOW - Already thread-safe |
| `RainManager` static lists | `ArrayList<IsoRainSplash>`, `ArrayList<IsoRaindrop>`, `int numActiveRainSplashes`, `int numActiveRaindrops` | Line 3189: `RainManager.RemoveAllOn(sq)` | HIGH - ArrayList not thread-safe |
| `IsoWorld.instance.currentCell` | `IsoCell` singleton | Line 3205: `IsoWorld.instance.currentCell.getSurvivorList().remove(obj)` | HIGH - Concurrent modification |
| `AnimalPopulationManager.getInstance()` | Static singleton | Line 3214: `virtualizeAnimalForUnload()` | MEDIUM - Singleton with internal state |
| `MapCollisionData.instance` | Static singleton | Line 3163: `removeChunkFromWorld()` | HIGH - Collision data accessed by pathfinding thread |
| `ZombiePopulationManager.instance` | Static singleton with `saveLock` (ReentrantLock) | Line 3164: `removeChunkFromWorld()` | MEDIUM - Has locking but scope may be insufficient |
| `PathfindNative.instance` / `PolygonalMap2.instance` | Static singletons | Lines 3172-3175: `removeChunkFromWorld()` | HIGH - Pathfinding data accessed by LOS thread |

### ZombiePopulationManager.removeChunkFromWorld() (Lines 348-407)

| Static Field | Type | Mutation Location | Risk Level |
|--------------|------|-------------------|------------|
| `saveLock` | `ReentrantLock` | Line 364: `saveLock.lock()` / `unlock()` | LOW - Already has locking |
| Internal zombie data structures | Native storage | `n_addZombie()` adds to virtual zombie storage | MEDIUM - Native code may have its own locking |

### AnimalPopulationManager.removeChunkFromWorld() (Lines 63-130)

| Static Field | Type | Mutation Location | Risk Level |
|--------------|------|-------------------|------------|
| `newChunks` | `TIntHashSet` | Line 122: `this.newChunks.remove(key)` | HIGH - TIntHashSet not thread-safe |
| Internal animal data structures | Native storage | `n_addAnimal()` adds to virtual animal storage | MEDIUM - Native code may have its own locking |

## Thread Safety Risks by Category

### CRITICAL RISKS (Must fix)

1. **RainManager static lists**
   - ArrayList modifications from multiple threads
   - Fix: Wrap RainManager operations with synchronized block or use concurrent collections
   ```java
   public static synchronized void RemoveAllOn(IsoGridSquare sq) {
       // existing code
   }
   ```

2. **IsoWorld.instance.currentCell survivor list**
   - Concurrent modification risk
   - Fix: Synchronize survivor list access or use concurrent collection
   ```java
   synchronized (IsoWorld.instance.currentCell.getSurvivorList()) {
       IsoWorld.instance.currentCell.getSurvivorList().remove(obj);
   }
   ```

3. **MapCollisionData.instance**
   - Collision data accessed by pathfinding thread
   - Fix: Ensure removeChunkFromWorld is synchronized with pathfinding access
   ```java
   public synchronized void removeChunkFromWorld(IsoChunk chunk) {
       // existing code
   }
   ```

4. **PathfindNative/PolygonalMap2**
   - Pathfinding data accessed by LOS thread
   - Fix: Synchronize chunk removal with pathfinding/LOS access
   ```java
   public synchronized void removeChunkFromWorld(IsoChunk chunk) {
       // existing code
   }
   ```

5. **AnimalPopulationManager.newChunks**
   - TIntHashSet not thread-safe
   - Fix: Use concurrent TIntHashSet or synchronize access
   ```java
   private final TIntHashSet newChunks = TCollections.synchronizedSet(new TIntHashSet());
   ```

### MEDIUM RISKS (Should verify)

1. **ZombiePopulationManager saveLock**
   - Lock exists but scope may be insufficient
   - Fix: Verify lock covers all mutations, consider widening scope
   - Ensure `n_addZombie()` and all internal mutations are within the lock

2. **AnimalPopulationManager internal state**
   - Singleton state mutations
   - Fix: Add locking to AnimalPopulationManager methods
   ```java
   public synchronized void virtualizeAnimalForUnload(IsoAnimal realAnimal) {
       // existing code
   }
   ```

### LOW RISKS (Already safe)

1. **IsoChunk.loadGridSquare**
   - Already ConcurrentLinkedQueue
   - No changes needed

## Additional Considerations

### Chunk Ownership Transfer
- Main thread must mark chunk as "unloading" before handing off to worker
- Worker must not access chunk until ownership is fully transferred
- Need a chunk state machine: LOADED → UNLOADING → UNLOADED

### Memory Visibility
- All shared state changes need proper memory barriers
- Use `volatile` for state flags
- Ensure happens-before relationship between main thread and worker

### Exception Handling
- Worker thread exceptions must not crash server
- Need robust error handling and recovery

### Shutdown Safety
- Must ensure all unload workers complete before server shutdown
- Need worker thread pool with proper shutdown hooks

## Summary

**Total static field mutations: 7**
- **High risk: 5** (RainManager, IsoWorld survivor list, MapCollisionData, PathfindNative, AnimalPopulationManager newChunks)
- **Medium risk: 2** (ZombiePopulationManager, AnimalPopulationManager internal state)
- **Low risk: 1** (IsoChunk loadGridSquare - already safe)

**Estimated effort:** 2-3 days to implement and test all synchronization changes.

## Alternative Approach

Instead of full async unload, consider:
1. **Batching unload operations** - Process multiple chunks in batches with synchronized access
2. **Dedicated unload thread with synchronized access** - Single worker thread with proper synchronization to critical sections
3. **Partial async** - Only async the square iteration loop while keeping manager calls synchronized

These alternatives would be safer than full async but still provide performance benefits by reducing main thread blocking time.

## Recommendation

Given the complexity and number of thread safety issues, the recommended approach is:
1. First implement the animal virtualization consolidation (already completed - 33% reduction)
2. Evaluate the performance improvement from that change
3. If still insufficient, implement a dedicated unload thread with synchronized access to critical sections
4. Full async unload should be considered only as a last resort due to the high complexity and risk
