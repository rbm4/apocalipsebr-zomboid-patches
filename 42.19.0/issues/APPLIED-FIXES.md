# Applied Fixes for Server Hang Issue

## Summary

Applied two critical fixes to address the server hang caused by lock contention between chunk load and save operations:

1. **Increased unload drain rates** - Reduces concurrent unloading cells and save queue pressure
2. **Lock contention check** - Prevents main thread from blocking on chunks being saved

---

## Fix 1: Increased Unload Drain Rates

**File:** `ServerMap.java:94-101`

**Changes:**
```java
// BEFORE:
private static final int UNLOAD_SLICES_NORMAL = 1;
private static final int UNLOAD_SLICES_WARNING = 4;
private static final int UNLOAD_SLICES_STRESS = 8;
private static final int UNLOAD_SLICES_EMERGENCY = 12;
private static final int UNLOAD_CELLS_NORMAL = 1;
private static final int UNLOAD_CELLS_WARNING = 1;
private static final int UNLOAD_CELLS_STRESS = 2;
private static final int UNLOAD_CELLS_EMERGENCY = 3;

// AFTER:
private static final int UNLOAD_SLICES_NORMAL = 2;      // +100%
private static final int UNLOAD_SLICES_WARNING = 6;     // +50%
private static final int UNLOAD_SLICES_STRESS = 12;     // +50%
private static final int UNLOAD_SLICES_EMERGENCY = 16;  // +33%
private static final int UNLOAD_CELLS_NORMAL = 2;       // +100%
private static final int UNLOAD_CELLS_WARNING = 2;      // +100%
private static final int UNLOAD_CELLS_STRESS = 3;       // +50%
private static final int UNLOAD_CELLS_EMERGENCY = 4;    // +33%
```

**Impact:**
- **Normal mode:** 2 cells × 2 slices = 4× throughput (was 1 cell × 1 slice)
- **Warning mode:** 2 cells × 6 slices = 12× throughput (was 1 cell × 4 slices)
- **Stress mode:** 3 cells × 12 slices = 36× throughput (was 2 cells × 8 slices)
- **Emergency mode:** 4 cells × 16 slices = 64× throughput (was 3 cells × 12 slices)

**Benefits:**
- ✅ Reduces number of concurrent unloading cells
- ✅ Reduces save queue depth
- ✅ Reduces probability of lock contention
- ✅ Cells unload faster, freeing memory sooner
- ✅ Adaptive - scales up under pressure

**Risks:**
- ⚠️ Slightly higher frame time during unload (still budgeted)
- ⚠️ More aggressive unload may cause brief frame hitches

---

## Fix 2: Lock Contention Check Before SafeRead

**File:** `PlayerDownloadServer.java:306-310, 373-377`

**Changes:**

### Location 1: sendLargeArea method (line 306-310)
```java
// BEFORE:
File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
if (inFile.exists()) {
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    this.sendChunk(reqChunk);
    ccr.releaseBuffer(reqChunk);
}

// AFTER:
File inFile = ChunkMapFilenames.instance.getFilename(wx, wy);
if (inFile.exists()) {
    // Check if chunk is being saved - if so, skip and let client retry
    if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
        if (PlayerDownloadServer.this.networkFileDebug) {
            DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": deferred - chunk being saved");
        }
        continue;  // Skip this chunk - client will retry after 8-second timeout
    }
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    this.sendChunk(reqChunk);
    ccr.releaseBuffer(reqChunk);
}
```

### Location 2: sendArray method (line 373-377)
```java
// BEFORE:
} else {
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    boolean addx = true;
    // ...
}

// AFTER:
} else {
    // Check if chunk is being saved - if so, skip and let client retry
    if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
        if (PlayerDownloadServer.this.networkFileDebug) {
            DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": deferred - chunk being saved");
        }
        continue;  // Skip this chunk - client will retry after 8-second timeout
    }
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    boolean addx = true;
    // ...
}
```

**How It Works:**
1. Player requests chunk (wx, wy)
2. Server checks: `hasPendingOrRunningSave(wx, wy)`
3. If chunk is being saved:
   - Skip `SafeRead()` call (avoids blocking on write lock)
   - Don't send chunk to client
   - Client doesn't receive chunk
4. After 8 seconds, client automatically retries (vanilla behavior)
5. By then, save is likely complete
6. Chunk is loaded and sent successfully

**Benefits:**
- ✅ **Main thread never blocks** - no hang
- ✅ **Client auto-retries** - proven mechanism (WorldStreamer.java:669-678)
- ✅ **Simple, safe fix** - no complex timeout logic
- ✅ **No data loss** - chunk is saved, just not loaded immediately
- ✅ **Minimal code changes** - two small checks

**Trade-offs:**
- ⚠️ **Slightly longer load time** - player waits extra 8-16 seconds for chunks being saved
- ⚠️ **Not a root cause fix** - doesn't solve slow I/O, just avoids blocking on it

---

## Root Cause Analysis

### The Problem
**Lock contention between chunk load (main thread) and save (background thread):**

1. SaveChunkThread holds WRITE LOCK on chunk (wx, wy)
2. `FileOutputStream.write()` blocks on slow I/O (NFS, disk contention) for 30+ seconds
3. Player returns to area, main thread tries to load same chunk
4. Main thread calls `SafeRead()` → tries to acquire READ LOCK
5. READ LOCK blocks waiting for WRITE LOCK to release
6. **Main thread hangs** - no frames, no network, server appears frozen

### Why It Happens Now (Not in Vanilla)
- **Vanilla:** Rare unloads, low probability of load/save collision
- **Patched:** 19 cells unloading concurrently → 1,216 chunks potentially being saved
- **Result:** HIGH probability of collision, frequent hangs

### The Lock Mechanism
`ReentrantReadWriteLock` (IsoChunk.java:5478):
- Write lock is exclusive
- Read lock blocks if write lock is held (by design)
- Write lock is held during entire I/O operation (can be 30+ seconds)

---

## Testing Strategy

### Before Testing
**Reproduce the hang:**
1. Multiple players exploring rapidly (trigger many unloads)
2. Player returns to recently unloaded area
3. Monitor for main thread hang (no frames, no network)
4. Check telemetry: `serverMapUnload.pending=19`, `oldestMs=73788`

### After Testing
**Expected behavior:**
1. Multiple players exploring rapidly
2. Player returns to recently unloaded area
3. **No hang** - server continues processing frames
4. Player sees "loading chunks" for 8-16 seconds longer
5. Chunks eventually load successfully
6. Telemetry: Lower `pending` count, lower `oldestMs`

### Metrics to Monitor
- `serverMapUnload.pending` - should be lower (faster drain)
- `serverMapUnload.oldestMs` - should be lower (faster processing)
- `serverMapUnload.unloaded` - should be higher (more throughput)
- Server frame time - should remain stable (no hang)
- Player chunk load time - may be slightly longer (8-16s for chunks being saved)

### Debug Logging
Enable `NetworkFileDebug` to see deferred chunk messages:
```
[NetworkFileDebug] 100,50: deferred - chunk being saved
```

---

## Additional Recommendations (Not Yet Applied)

### Optional Fix 3: Bounded Save Queue
**Purpose:** Prevent memory exhaustion from unbounded save queue

**Implementation:**
```java
// ServerChunkLoader.java:490
this.toThread = new LinkedBlockingQueue<>(256);  // Limit to 256 saves
```

**When to apply:**
- If memory issues persist
- If save queue depth exceeds 200-300 chunks
- As additional safety measure

### Optional Fix 4: SaveChunkThread Watchdog
**Purpose:** Detect and log I/O stalls for diagnostics

**Implementation:**
```java
// Track save start time, log if exceeds 10 seconds
if (saveTimeMs > 10000) {
    DebugLog.log("WARN: Chunk save took " + saveTimeMs + "ms for " + wx + "," + wy);
}
```

**When to apply:**
- If I/O stalls continue
- For production diagnostics
- To identify slow storage issues

---

## Summary

**Applied fixes:**
1. ✅ Increased unload drain rates (2-4× throughput)
2. ✅ Lock contention check before SafeRead (prevents hang)

**Expected outcome:**
- ✅ No more server hangs
- ✅ Faster unload processing
- ✅ Lower save queue depth
- ⚠️ Slightly longer chunk load time (8-16s for chunks being saved)

**Next steps:**
1. Test in production environment
2. Monitor telemetry metrics
3. Apply optional fixes if needed (bounded queue, watchdog)
4. Adjust drain rates if frame time impact is too high
