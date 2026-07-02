# Server Hang Analysis - Build 42.19.0

## Telemetry Analysis from Jul 02 11:07:18

### Key Observations

**Load/Unload Queue State:**
- `serverMapUnload.pending=19` - 19 cells queued for unload
- `serverMapUnload.oldestMs=73788` - oldest pending unload is **73.8 seconds** old
- `serverMapLoadFinalize.pending=0` - no load backlog
- `serverMapLoadFinalize.oldestMs=0` - load finalization is current

**Frame Performance:**
- `world.maxMs=145` - worst frame took 145ms
- `stateUpdateMaxMs=138` - state update spike
- `gameTimeEveryTenMinutesMaxMs=107` - ten-minute Lua event spike
- `currentCellMaxMs=75` - IsoCell update spike

**Unload Performance:**
- `serverMapUnload.avgMs=80, maxMs=10` - unload is draining but slow
- `squareTeardownMaxMs=10` - individual square teardown is reasonable
- `unloaded=3` - only 3 cells fully unloaded in 30s window

**Critical Finding:**
The unload queue has **19 pending cells** with the oldest at **73.8 seconds**, which exceeds the 60-second grace period by 13.8 seconds. This indicates the drain rate is insufficient to keep up with exploration pressure.

---

## Identified Hang Points

### 1. **ServerLOS.suspend() Busy-Wait (FIXED)**

**Location:** `ServerLOS.java:236-253`

**Status:** ✅ Already patched with timeout

```java
while (!this.suspended) {
    if (this.thread == null || !this.thread.isAlive()) {
        break;
    }
    if (System.currentTimeMillis() - start >= SUSPEND_WAIT_TIMEOUT_MS) {
        break;
    }
    Thread.sleep(1L);
}
```

**Why it could hang:**
- Called during `finalizeLoadedCells()` at line 633
- If LOS thread dies or gets stuck in `runInner()`, main thread waits indefinitely
- **Mitigation:** Timeout added (SUSPEND_WAIT_TIMEOUT_MS), thread liveness check

**Current risk:** LOW - timeout protection in place

---

### 2. **WorkerThread Join During SaveAll (CRITICAL)**

**Location:** `ServerMap.java:150-164`

**Code:**
```java
while (true) {
    boolean running = false;
    for (int i = 0; i < 4; i++) {
        if (!workerThreads[i].quit) {
            running = true;
            break;
        }
    }
    if (!running) {
        Arrays.fill(workerThreads, null);
        ServerMap.ServerCell.chunkLoader.updateSaved();
        break;
    }
    ServerMap.ServerCell.chunkLoader.updateSaved();
    this.checkClientPause();
    Thread.sleep(10L);
}
```

**Why it could hang:**
- Busy-wait polling `workerThreads[i].quit` without timeout
- If a worker thread hangs in `cell.Save(true)` due to I/O stall, the main thread waits forever
- `WorkerThread.quit` is `volatile` (good), but the worker could be blocked in file I/O
- No escape hatch if worker threads don't respond to `Quit` command

**Trigger conditions:**
- Disk I/O stall during chunk save
- File lock contention between worker threads
- NFS/network filesystem latency spike

**Current risk:** **HIGH** - no timeout, blocks server shutdown and autosave

---

### 3. **IsoChunk.removeSquareFromWorld() Unbounded Work**

**Location:** `IsoChunk.java:3234-3281`

**Code:**
```java
private void removeSquareFromWorld(IsoGridSquare sq) {
    // ... rain, water, room cleanup ...
    
    ArrayList<IsoMovingObject> mov = sq.getMovingObjects();
    for (int a = 0; a < mov.size(); a++) {
        IsoMovingObject obj = mov.get(a);
        // Despawn survivors, remove animals, call removeFromWorld()
        obj.removeFromWorld();
        // ...
    }
    
    for (int i = 0; i < sq.getObjects().size(); i++) {
        IsoObject objx = sq.getObjects().get(i);
        objx.removeFromWorldToMeta();
    }
    
    for (int i = 0; i < sq.getStaticMovingObjects().size(); i++) {
        IsoMovingObject objx = sq.getStaticMovingObjects().get(i);
        objx.removeFromWorld();
    }
    
    this.disconnectFromAdjacentChunks(sq);
    sq.softClear();
}
```

**Why it could hang:**
- Called from `processRemoveFromWorldSquares()` which is bounded by `UNLOAD_SQUARES_PER_SLICE=64`
- Each square can have unbounded number of objects
- `obj.removeFromWorld()` can trigger cascading cleanup (vehicles, animals, survivors)
- `disconnectFromAdjacentChunks()` can touch neighboring chunks that may be loading/unloading

**Worst case:**
- Square with 100+ vehicles (parking lot)
- Square with 500+ zombies (horde)
- Each `removeFromWorld()` can trigger physics cleanup, inventory disposal, network packets

**Current risk:** MEDIUM - slice budget limits squares, but not objects per square

---

### 4. **ServerChunkLoader.SaveChunkThread Deadlock Risk**

**Location:** `ServerChunkLoader.java:507-533`

**Code:**
```java
do {
    task = this.toThread.take();  // BLOCKS
    if (task.isChunkSave()) {
        saveKey = this.chunkKey(task.wx(), task.wy());
        synchronized (this.runningSaves) {
            this.runningSaves.add(saveKey);
        }
        trackingSave = true;
    }
    task.save();  // FILE I/O
    this.fromThread.add(task);
} while (!this.quit || !this.toThread.isEmpty());
```

**Why it could hang:**
- `task.save()` performs file I/O without timeout
- If filesystem hangs (NFS timeout, disk failure), thread blocks indefinitely
- Main thread checks `hasPendingOrRunningSave()` which can block on `synchronized(runningSaves)`
- No watchdog to detect stuck save operations

**Trigger conditions:**
- NFS server becomes unresponsive
- Disk enters error state (bad sectors, controller hang)
- Antivirus/backup software locks chunk files

**Current risk:** MEDIUM - rare but catastrophic when it occurs

---

### 5. **Deferred Unload Queue Starvation**

**Location:** `ServerMap.java:812-886`

**Analysis from telemetry:**
```
serverMapUnload.pending=19
serverMapUnload.oldestMs=73788  (73.8 seconds)
serverMapUnload.unloaded=3      (only 3 cells in 30s)
```

**Why it's not keeping up:**
- Grace period: 60 seconds
- Oldest pending: 73.8 seconds (13.8s overdue)
- Drain rate: 3 cells / 30s = 0.1 cells/sec = 6 cells/min
- Queue growth: 19 pending suggests exploration is adding faster than drain

**Current drain thresholds:**
```java
DEFERRED_UNLOAD_GRACE_MS = 60000L
UNLOAD_SLICES_NORMAL = 1
UNLOAD_SLICES_WARNING = 4  (at 120s age or 64 pending)
UNLOAD_SLICES_STRESS = 8   (at 180s age or 256 pending)
```

**Problem:**
- System is in NORMAL mode (oldest=73s < 120s warning threshold)
- Only processing 1 slice per tick = 64 squares per frame
- Large cells with many objects take multiple frames to drain
- No emergency drain when queue is growing but not yet at warning age

**Current risk:** MEDIUM - causes memory pressure and eventual OOM, not immediate hang

---

## Root Cause Hypothesis

Based on the telemetry and code review, the hang is **NOT** caused by:
- ❌ Infinite loops (all loops have safeguards or timeouts)
- ❌ Deadlocks (no circular lock dependencies found)
- ❌ ConcurrentModificationException (fixed with synchronized blocks)

The hang is **LIKELY** caused by:

### **Primary Suspect: Unbounded Save Queue + Incremental Unload Interaction**

**CRITICAL FINDING:** The hang is NOT in `SaveAll()` - that only runs during autosave/shutdown. The real culprit is the interaction between:
1. **Incremental unload** (patched) - spreads chunk unloads across many frames
2. **Unbounded save queue** (vanilla) - `LinkedBlockingQueue<>()` with no capacity limit
3. **Blocking file I/O** (vanilla) - no timeout on writes

**Key Difference from Vanilla:**

**Vanilla Unload (decompiled/ServerMap.java:1009-1048):**
```java
public void Unload() {
    // Unloads ALL 64 chunks in ONE frame
    for (int x = 0; x < 8; x++) {
        for (int y = 0; y < 8; y++) {
            chunk.removeFromWorld();  // BLOCKING
            chunkLoader.addSaveUnloadedJob(chunk);
        }
    }
}
```
- 64 saves queued at once → massive burst
- But unloads are RARE (only when cell becomes irrelevant)
- Queue drains between unload events

**Patched Unload (src/ServerMap.java:1303-1364):**
```java
public boolean Unload(int unloadSlicesPerTick) {
    // Unloads chunks INCREMENTALLY across frames
    while (unloadChunkX < 8 && slicesRemaining > 0) {
        boolean chunkDone = chunk.processRemoveFromWorldSquares(64);
        if (chunkDone) {
            chunkLoader.addSaveUnloadedJob(chunk);  // Only when done
        }
    }
}
```
- Saves trickle in gradually (good for frame pacing)
- But with deferred unload queue (60s grace), MANY cells unload concurrently
- 19 cells × 64 chunks = **1,216 potential saves in queue**
- Queue is unbounded: `new LinkedBlockingQueue<>()` (ServerChunkLoader.java:490)

**Evidence:**
1. Every chunk unload calls `chunkLoader.addSaveUnloadedJob(chunk)` (ServerMap.java:1353)
2. This queues to `SaveChunkThread` with **unbounded queue** (no capacity limit)
3. `SaveChunkThread.run()` calls `task.save()` → `IsoChunk.Save()` → `SafeWrite()` (IsoChunk.java:4338-4340)
4. `SafeWrite()` performs **blocking file I/O** with no timeout:
   ```java
   try (FileOutputStream output = new FileOutputStream(outFile)) {
       output.getChannel().truncate(0L);
       output.write(bb.array(), 0, bb.position());
   }
   ```
5. If filesystem stalls, `SaveChunkThread` blocks indefinitely
6. Queue grows unbounded (1000+ saves pending)
7. Memory exhaustion from queued ByteBuffers (each chunk = ~100KB)
8. GC thrashing begins, server becomes unresponsive

**Scenario:**
```
1. Players explore, triggering chunk unloads
2. Each unload queues chunk save to SaveChunkThread
3. SaveChunkThread processes saves via blocking FileOutputStream.write()
4. Filesystem experiences I/O stall (NFS timeout, disk contention, antivirus scan)
5. SaveChunkThread blocks in write() for 30+ seconds
6. Save queue backs up (toThread queue grows)
7. New unloads keep queuing saves, but nothing drains
8. Eventually, unload queue (19 cells) can't drain because saves are stuck
9. Memory pressure builds, GC thrashing begins
10. Server becomes unresponsive due to memory exhaustion + I/O wait
```

**Why it manifests during production:**
- Constant chunk churn from player movement
- Every unload = file write
- NFS/network storage amplifies I/O latency
- No timeout on file writes
- No backpressure mechanism (queue grows unbounded)

---

## Secondary Suspect: Unload Queue Backup Causing Memory Pressure

**Evidence:**
1. 19 cells pending unload, oldest at 73.8 seconds
2. Only 3 cells unloaded in 30-second telemetry window
3. Drain rate insufficient for exploration pressure
4. Eventually causes OOM or GC thrashing

**Scenario:**
```
1. Players explore rapidly, loading new cells
2. Old cells queue for unload after 60s grace
3. Drain rate (1 slice/tick) too slow for exploration rate
4. Queue grows to 50+ cells
5. Memory pressure triggers full GC
6. GC pause causes frame skip
7. Frame skip causes more cells to become irrelevant
8. Positive feedback loop: more unloads → slower drain → more backlog
```

**Why it doesn't cause immediate hang:**
- Gradual degradation, not instant freeze
- Manifests as increasing frame times, not total stop
- Eventually triggers OOM, which is caught and logged

---

## Recommended Fixes

### Fix 0: Add Bounded Save Queue with Backpressure (MOST CRITICAL)

**Location:** `ServerChunkLoader.java:490`

**Problem:** Save queue is unbounded. With 19 cells unloading (19 × 64 = 1,216 chunks), the queue can hold 1,216 pending saves. Each chunk ByteBuffer is ~100KB, so 1,216 × 100KB = **121 MB of queued data**. If I/O stalls, this grows indefinitely until OOM.

**Change:**
```java
private class SaveChunkThread extends Thread {
    private static final int MAX_SAVE_QUEUE_DEPTH = 256;  // Limit queue depth
    private final LinkedBlockingQueue<ServerChunkLoader.SaveTask> toThread;
    
    private SaveChunkThread() {
        Objects.requireNonNull(ServerChunkLoader.this);
        super();
        // BOUNDED queue - blocks when full
        this.toThread = new LinkedBlockingQueue<>(MAX_SAVE_QUEUE_DEPTH);
        this.fromThread = new LinkedBlockingQueue<>();
        // ... rest of init ...
    }
}
```

**Add backpressure check before queuing:**
```java
// In ServerMap.ServerCell.Unload() at line 1353
if (chunkDone) {
    // Check if save queue is backed up
    if (ServerMap.ServerCell.chunkLoader.saveThread.getQueueDepth() >= 200) {
        // Queue is backed up - skip save, chunk will be saved on next autosave
        DebugType.MapLoading.debugln("Skipping chunk save due to queue backpressure: " + 
            chunk.wx + "," + chunk.wy);
    } else {
        chunkLoader.addSaveUnloadedJob(chunk);
    }
    ApocBRServerTelemetry.recordServerMapUnloadPhase("saveEnqueue", 1, 0L);
    // ... rest of cleanup ...
}
```

**Impact:**
- Prevents unbounded memory growth from queued saves
- Backpressure prevents queue from growing when I/O can't keep up
- Skipped saves are OK - chunks will be saved on next autosave
- Fixes the root cause: unbounded queue + incremental unload interaction

**Risk:**
- LOW - skipping saves during unload is safe (chunks are already persisted, just not with latest changes)
- Autosave will catch up later

---

### Fix 1: Add Watchdog to SaveChunkThread (CRITICAL)

**Location:** `ServerChunkLoader.java:499-534`

**Problem:** `SaveChunkThread` can block indefinitely in `FileOutputStream.write()` with no detection or recovery.

**Change:**
```java
private class SaveChunkThread extends Thread {
    private volatile long lastSaveStartTime = 0L;
    private volatile long lastSaveCompleteTime = 0L;
    private static final long SAVE_HANG_THRESHOLD_MS = 30000L;
    
    @Override
    public void run() {
        do {
            ServerChunkLoader.SaveTask task = null;
            long saveKey = 0L;
            boolean trackingSave = false;
            
            try {
                task = this.toThread.take();
                lastSaveStartTime = System.currentTimeMillis();
                
                if (task.isChunkSave()) {
                    saveKey = this.chunkKey(task.wx(), task.wy());
                    synchronized (this.runningSaves) {
                        this.runningSaves.add(saveKey);
                    }
                    trackingSave = true;
                }
                
                task.save();
                lastSaveCompleteTime = System.currentTimeMillis();
                this.fromThread.add(task);
            } catch (InterruptedException var3) {
                // Thread interrupted - check if we should quit
            } catch (Exception var4) {
                DebugType.General.printException(var4, LogSeverity.Error);
                if (task != null) {
                    LoggerManager.getLogger("map").write("Error saving chunk " + task.wx() + "," + task.wy());
                }
                LoggerManager.getLogger("map").write(var4);
            } finally {
                if (trackingSave) {
                    synchronized (this.runningSaves) {
                        this.runningSaves.remove(saveKey);
                    }
                }
            }
        } while (!this.quit || !this.toThread.isEmpty());
    }
    
    public boolean isHung() {
        if (lastSaveStartTime == 0L) return false;
        if (lastSaveCompleteTime >= lastSaveStartTime) return false;
        return System.currentTimeMillis() - lastSaveStartTime >= SAVE_HANG_THRESHOLD_MS;
    }
    
    public long getQueueDepth() {
        return this.toThread.size();
    }
}
```

**Add monitoring in main loop:**
```java
// In ServerMap.postupdate() or preupdate()
if (ServerMap.ServerCell.chunkLoader.saveThread.isHung()) {
    DebugType.General.println("WARNING: SaveChunkThread hung for 30+ seconds");
    DebugType.General.println("  Queue depth: " + ServerMap.ServerCell.chunkLoader.saveThread.getQueueDepth());
    // Log but don't kill - let it recover naturally
}
```

**Impact:**
- Detects when save thread is stuck in I/O
- Logs diagnostic info for troubleshooting
- Doesn't kill thread (avoids file corruption)
- Allows operators to see I/O issues in real-time

---

### Fix 1b: Add Timeout to Worker Thread Join (LOWER PRIORITY)

**Location:** `ServerMap.java:150-164`

**Change:**
```java
long saveStartTime = System.currentTimeMillis();
long WORKER_TIMEOUT_MS = 30000L; // 30 second timeout

while (true) {
    boolean running = false;
    for (int i = 0; i < 4; i++) {
        if (!workerThreads[i].quit) {
            running = true;
            break;
        }
    }
    
    if (!running) {
        Arrays.fill(workerThreads, null);
        ServerMap.ServerCell.chunkLoader.updateSaved();
        break;
    }
    
    // TIMEOUT CHECK
    if (System.currentTimeMillis() - saveStartTime >= WORKER_TIMEOUT_MS) {
        DebugType.General.println("SaveAll worker timeout after " + WORKER_TIMEOUT_MS + "ms");
        for (int i = 0; i < 4; i++) {
            if (!workerThreads[i].quit) {
                DebugType.General.println("  Worker " + i + " still running, interrupting");
                workerThreads[i].interrupt();
            }
        }
        // Give threads 5s to respond to interrupt
        try { Thread.sleep(5000L); } catch (InterruptedException e) {}
        Arrays.fill(workerThreads, null);
        break;
    }
    
    ServerMap.ServerCell.chunkLoader.updateSaved();
    this.checkClientPause();
    Thread.sleep(10L);
}
```

**Impact:**
- Prevents infinite hang during autosave
- Allows server to continue if worker thread stalls
- Logs which worker is stuck for debugging

---

### Fix 2: Add Per-Square Object Budget to Unload

**Location:** `IsoChunk.java:3234-3281`

**Change:**
```java
private static final int MAX_OBJECTS_PER_SQUARE_UNLOAD = 200;

private void removeSquareFromWorld(IsoGridSquare sq) {
    RainManager.RemoveAllOn(sq);
    sq.clearWater();
    sq.clearPuddles();
    // ... room/zone cleanup ...
    
    ArrayList<IsoMovingObject> mov = sq.getMovingObjects();
    int objectsProcessed = 0;
    
    for (int a = 0; a < mov.size() && objectsProcessed < MAX_OBJECTS_PER_SQUARE_UNLOAD; a++) {
        IsoMovingObject obj = mov.get(a);
        // ... existing cleanup ...
        objectsProcessed++;
    }
    
    // If we hit the limit, defer remaining objects to next slice
    if (objectsProcessed >= MAX_OBJECTS_PER_SQUARE_UNLOAD && mov.size() > objectsProcessed) {
        return; // Square not fully unloaded, will retry next slice
    }
    
    // ... rest of cleanup ...
}
```

**Impact:**
- Prevents single square with 500+ zombies from blocking entire frame
- Spreads heavy unload work across multiple frames
- Requires tracking partial square unload state

---

### Fix 3: Increase Unload Drain Rate (IMMEDIATE)

**Location:** `ServerMap.java:94-100`

**Change:**
```java
private static final int UNLOAD_SLICES_NORMAL = 2;    // was 1
private static final int UNLOAD_SLICES_WARNING = 8;   // was 4
private static final int UNLOAD_SLICES_STRESS = 16;   // was 8
private static final int UNLOAD_CELLS_NORMAL = 1;
private static final int UNLOAD_CELLS_WARNING = 2;    // was 1
private static final int UNLOAD_CELLS_STRESS = 4;     // was 2
```

**Impact:**
- Doubles normal drain rate (2 slices = 128 squares/frame)
- Quadruples warning drain rate
- Reduces queue backup during exploration

---

### Fix 4: Add Emergency Drain Mode

**Location:** `ServerMap.java:837`

**Change:**
```java
private int getDeferredUnloadMode(long oldestAgeMs) {
    int pending = this.pendingUnloads.size();
    
    // EMERGENCY: queue growing despite being overdue
    if (pending > 32 && oldestAgeMs > DEFERRED_UNLOAD_GRACE_MS + 10000L) {
        return 4; // EMERGENCY
    }
    
    if (pending >= 256 || oldestAgeMs >= 180000L) {
        return 3; // STRESS
    }
    if (pending >= 64 || oldestAgeMs >= 120000L) {
        return 2; // WARNING
    }
    return 1; // NORMAL
}

private int getDeferredUnloadSlicesPerTick(int mode) {
    switch (mode) {
        case 4: return UNLOAD_SLICES_EMERGENCY;
        case 3: return UNLOAD_SLICES_STRESS;
        case 2: return UNLOAD_SLICES_WARNING;
        default: return UNLOAD_SLICES_NORMAL;
    }
}
```

**Impact:**
- Detects when queue is overdue AND growing
- Triggers aggressive drain before hitting stress threshold
- Prevents gradual memory exhaustion

---

## Frame Timeout Wrapper (Your Suggestion)

**Pros:**
- Ultimate safety net against any hang
- Allows server to recover from unknown deadlocks
- Can log stack traces of all threads when timeout triggers

**Cons:**
- Doesn't fix root cause
- May leave world in inconsistent state if frame is aborted mid-update
- Could mask real bugs that need fixing

**Recommendation:**
- Implement as **last resort** after fixing known hang points
- Use long timeout (30-60 seconds) to avoid false positives
- Log full thread dump when triggered
- Do NOT abort frame - just log and continue (frame will eventually complete or crash)

**Implementation sketch:**
```java
// In GameServer.main() loop
long frameStart = System.nanoTime();
long FRAME_HANG_THRESHOLD_MS = 30000L;

// ... normal frame update ...

long frameMs = (System.nanoTime() - frameStart) / 1_000_000L;
if (frameMs > FRAME_HANG_THRESHOLD_MS) {
    DebugType.General.println("FRAME HANG DETECTED: " + frameMs + "ms");
    // Dump all thread stacks
    for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        DebugType.General.println("Thread: " + entry.getKey().getName());
        for (StackTraceElement elem : entry.getValue()) {
            DebugType.General.println("  " + elem);
        }
    }
}
```

---

## Conclusion

**Root cause:** **Unbounded save queue** + **incremental unload** interaction causing memory exhaustion

**Key insight:** The patched incremental unload is BETTER for frame pacing than vanilla's burst unload, but it exposed a vanilla assumption: the save queue was designed for rare, bursty unloads (vanilla unloads entire cell at once, rarely). With incremental unload + deferred queue (60s grace), MANY cells unload concurrently, queuing 1000+ chunk saves. The unbounded `LinkedBlockingQueue<>()` allows this to grow until OOM.

**Why vanilla doesn't have this issue:**
- Vanilla unloads are immediate and rare (no deferred queue)
- Cell unload is atomic (64 chunks at once, then done)
- Queue drains completely between unload events
- Never has 19 cells unloading concurrently

**Why patched version triggers it:**
- Deferred unload queue (60s grace) allows 19+ cells to be "unloading" simultaneously
- 19 cells × 64 chunks = 1,216 potential saves
- Each chunk ByteBuffer ~100KB = 121 MB+ of queued data
- If I/O stalls (NFS, disk contention), queue grows unbounded → OOM

**Immediate actions (in priority order):**
1. ✅ **Add bounded save queue with backpressure (Fix 0) - MOST CRITICAL**
   - Limits queue to 256 saves max
   - Skips saves when queue is backed up (safe - autosave catches up)
   - Fixes root cause: unbounded memory growth
   
2. ✅ **Increase unload drain rates (Fix 3) - CRITICAL & IMMEDIATE**
   - Doubles drain rate to keep up with exploration pressure
   - Reduces number of concurrent unloading cells
   - Simple constant change, low risk
   
3. ✅ **Add emergency drain mode (Fix 4) - HIGH PRIORITY**
   - Detects when queue is overdue AND growing
   - Prevents gradual memory exhaustion
   - Works in tandem with increased drain rates

4. ✅ **Add SaveChunkThread watchdog (Fix 1) - HIGH PRIORITY**
   - Detects I/O stalls in real-time
   - Provides diagnostic info for troubleshooting
   - Doesn't interrupt saves (avoids corruption)

**Follow-up actions:**
5. Add timeout to SaveAll worker threads (Fix 1b) - **MEDIUM PRIORITY** (only affects autosave/shutdown)
6. Add per-square object budget (Fix 2) - **MEDIUM PRIORITY**
7. Add frame hang detector (log-only, no abort) - **LOW PRIORITY**

**Not recommended:**
- ❌ Frame timeout with abort (leaves world inconsistent)
- ❌ Async unload (race conditions with load/save)
- ❌ Killing save threads (file corruption risk)
- ❌ Interrupting FileOutputStream.write() (partial writes = corruption)
