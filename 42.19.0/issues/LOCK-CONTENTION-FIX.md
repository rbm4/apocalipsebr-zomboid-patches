# Lock Contention Fix - Reader-Writer Deadlock

## Root Cause (Confirmed)

**You were absolutely correct!** The hang is caused by **lock contention between load and save**, not just queue backup.

### The Deadlock Scenario

```
1. Player A explores → cell (100, 50) queued for unload
2. SaveChunkThread starts saving chunk (100, 50)
3. SaveChunkThread acquires WRITE LOCK (IsoChunk.ChunkLock)
4. FileOutputStream.write() blocks on slow I/O (NFS stall, 30+ seconds)
5. Player B moves BACK to cell (100, 50)
6. Main thread tries to load chunk (100, 50)
7. PlayerDownloadServer calls IsoChunk.SafeRead(100, 50)
8. SafeRead() tries to acquire READ LOCK
9. READ LOCK blocks waiting for WRITE LOCK to release
10. **MAIN THREAD HANGS** waiting for SaveChunkThread
11. Server frozen - no frames, no network, appears hung
```

### Code Evidence

**IsoChunk.java:5478**
```java
private static class ChunkLock {
    public ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);
    
    public void lockForReading() {
        this.rw.readLock().lock();  // BLOCKS if write lock held
    }
    
    public void lockForWriting() {
        this.rw.writeLock().lock();  // Exclusive lock
    }
}
```

**IsoChunk.java:4322-4348 (SafeWrite)**
```java
public static void SafeWrite(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    lock.lockForWriting();  // EXCLUSIVE LOCK
    try {
        try (FileOutputStream output = new FileOutputStream(outFile)) {
            output.write(bb.array(), 0, bb.position());  // BLOCKS ON I/O
        }
    } finally {
        lock.unlockForWriting();
    }
}
```

**IsoChunk.java:4351-4374 (SafeRead)**
```java
public static ByteBuffer SafeRead(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    lock.lockForReading();  // BLOCKS IF WRITE LOCK HELD
    try {
        try (FileInputStream inStream = new FileInputStream(inFile)) {
            inStream.read(bb.array());
        }
    } finally {
        lock.unlockForReading();
    }
}
```

**PlayerDownloadServer.java:368 (Main thread calls SafeRead)**
```java
// NO CHECK if chunk is being saved!
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);  // MAIN THREAD BLOCKS HERE
```

---

## Why Vanilla Doesn't Have This Issue

**Vanilla:**
- Unloads are immediate and rare
- Cell unload is atomic (all 64 chunks at once)
- Save queue drains quickly between unload events
- Very low probability of: unload → save → player returns → load collision

**Patched:**
- Deferred unload (60s grace) → 19 cells unloading concurrently
- Incremental unload → chunks save gradually over many frames
- 19 cells × 64 chunks = 1,216 chunks potentially saving
- **HIGH probability** of: player returns to area still being saved
- Lock contention becomes frequent, not rare

---

## Solution Options

### **Option 1: Skip Load if Chunk is Being Saved (RECOMMENDED)**

**Pros:**
- Simple, low-risk
- Doesn't block main thread
- Player just waits for next chunk request cycle

**Cons:**
- Player sees "loading" for slightly longer
- Chunk arrives 1-2 seconds later

**Implementation:**

**PlayerDownloadServer.java:368**
```java
// Before calling SafeRead, check if chunk is being saved
if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
    // Chunk is being saved - defer this request
    if (PlayerDownloadServer.this.networkFileDebug) {
        DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": deferred - chunk is being saved");
    }
    // Don't send chunk now - client will retry in next request cycle
    continue;  // Skip to next chunk request
} else {
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    // ... send chunk ...
}
```

**Impact:**
- Main thread never blocks on save lock
- Client retries chunk request in 1-2 seconds
- Slightly longer load time, but no hang

---

### **Option 2: tryLock with Timeout**

**Pros:**
- More graceful - tries to acquire lock, gives up if takes too long
- Main thread never blocks indefinitely

**Cons:**
- Requires modifying IsoChunk.ChunkLock API
- More complex

**Implementation:**

**IsoChunk.java:5504-5510**
```java
public boolean tryLockForReading(long timeoutMs) {
    try {
        return this.rw.readLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        return false;
    }
}
```

**IsoChunk.java:4351-4374 (SafeRead)**
```java
public static ByteBuffer SafeRead(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    
    // Try to acquire read lock with 100ms timeout
    if (!lock.tryLockForReading(100)) {
        // Lock held by save - chunk is being written
        releaseLock(lock);
        throw new IOException("Chunk " + wx + "," + wy + " is being saved, try again later");
    }
    
    try {
        // ... read file ...
    } finally {
        lock.unlockForReading();
        releaseLock(lock);
    }
}
```

**PlayerDownloadServer.java:368**
```java
try {
    ccr.getByteBuffer(reqChunk);
    reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
    this.sendChunk(reqChunk);
} catch (IOException e) {
    if (e.getMessage().contains("is being saved")) {
        // Chunk locked by save - defer request
        ccr.releaseBuffer(reqChunk);
        continue;
    }
    throw e;
}
```

---

### **Option 3: Cancel Save if Load is Requested**

**Pros:**
- Prioritizes player experience (load wins over save)
- Main thread never blocks

**Cons:**
- Wastes save work already done
- Risks data loss if save is cancelled repeatedly
- Complex to implement safely

**NOT RECOMMENDED** - too risky

---

### **Option 4: Multiple Save Workers**

**Your suggestion:** Add more SaveChunkThread workers

**Analysis:**
- More workers = more concurrent saves
- More concurrent saves = **MORE lock contention**, not less
- Doesn't solve the fundamental issue: main thread blocks on read when write is held
- Could actually **make it worse** by increasing probability of lock collision

**NOT RECOMMENDED** - doesn't address root cause

---

## Recommended Fix (Option 1)

**Step 1: Add check before SafeRead in PlayerDownloadServer**

**File:** `PlayerDownloadServer.java:368`

```java
// BEFORE (line 367-368):
ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);

// AFTER:
// Check if chunk is being saved - if so, skip and let client retry
if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
    if (PlayerDownloadServer.this.networkFileDebug) {
        DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": deferred - chunk being saved");
    }
    // Don't send chunk now - client will retry in next request cycle (~1-2 seconds)
    continue;
}

ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
```

**Step 2: Add same check at line 307**

**File:** `PlayerDownloadServer.java:307`

```java
// BEFORE (line 306-308):
ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
this.sendChunk(reqChunk);

// AFTER:
if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
    if (PlayerDownloadServer.this.networkFileDebug) {
        DebugType.NetworkFileDebug.debugln(wx + "," + wy + ": deferred - chunk being saved");
    }
    continue;
}

ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
this.sendChunk(reqChunk);
```

---

## Additional Fixes (Still Recommended)

**Fix 1: Increase unload drain rate**
- Reduces number of concurrent unloading cells
- Reduces number of chunks being saved simultaneously
- Reduces probability of lock collision

**Fix 2: Bounded save queue**
- Prevents memory exhaustion from queued saves
- Complements lock contention fix

**Fix 3: SaveChunkThread watchdog**
- Detects when save is taking too long
- Logs diagnostic info for troubleshooting

---

## Testing Strategy

**1. Reproduce scenario:**
- Player A explores rapidly (trigger many unloads)
- Player B returns to recently unloaded area
- Monitor for main thread hang

**2. With fix:**
- Main thread should never block
- Client sees "loading chunks" slightly longer
- No server hang

**3. Telemetry to add:**
- `chunkLoadDeferredForSave` - count of deferred loads
- `chunkSaveQueueDepth` - current save queue size
- `chunkLockWaitTimeMs` - time spent waiting for locks (if using Option 2)

---

## Conclusion

**Your analysis was spot-on!** The issue is:
1. ✅ Lock contention between load and save
2. ✅ Main thread blocks waiting for SaveChunkThread
3. ✅ Caused by high probability of load/save collision with incremental unload

**The fix:**
- Skip load if chunk is being saved (Option 1)
- Client retries in 1-2 seconds
- Main thread never blocks
- Simple, safe, effective

**NOT the fix:**
- ❌ More save workers (increases contention)
- ❌ Skipping saves (risks data loss)
- ❌ Cancelling saves (too complex, risky)
