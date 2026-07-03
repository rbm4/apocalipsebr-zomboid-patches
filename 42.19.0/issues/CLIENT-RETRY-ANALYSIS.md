# Client Chunk Request Retry Analysis

## Question 1: Does the client auto-retry chunk requests?

**YES! ✅** The client automatically retries chunk requests after an 8-second timeout.

### Evidence

**File:** `WorldStreamer.java:666-678` (decompiled vanilla)

```java
private void checkTimeouts() {
    long time = System.currentTimeMillis();
    
    for (int i = 0; i < this.pendingRequests1.size(); i++) {
        WorldStreamer.ChunkRequest request = this.pendingRequests1.get(i);
        
        // Check if request has timed out (8 seconds)
        if ((request.flagsWs & 1) == 0 && request.time + 8000L < time) {
            if (this.networkFileDebug) {
                DebugType.NetworkFileDebug.debugln(
                    "chunk request timed out " + request.chunk.wx + "," + request.chunk.wy
                );
            }
            
            // RE-ADD CHUNK TO REQUEST QUEUE - AUTOMATIC RETRY!
            this.chunkRequests1.add(request.chunk);
            request.flagsWs |= 9;
            request.flagsMain |= 2;
        }
    }
}
```

### How It Works

1. **Client requests chunk** (wx, wy) from server
2. **Request added to `pendingRequests1`** with timestamp
3. **Server processes request** via `PlayerDownloadServer.update()`
4. **If server doesn't respond within 8 seconds:**
   - Request is marked as timed out
   - Chunk is **re-added to `chunkRequests1` queue**
   - Client will **automatically retry** in next update cycle
5. **Client keeps retrying** until chunk is received or player moves away

### Impact on Our Fix

**This is PERFECT for our lock contention fix!**

If we skip loading a chunk because it's being saved:
- Client doesn't receive the chunk
- After 8 seconds, client **automatically retries**
- By then, save is likely complete (or we skip again and retry in another 8s)
- Player sees "loading chunks" for slightly longer, but **no hang**

---

## Question 2: Why does the lock hang indefinitely?

**The lock doesn't "hang" - it's working as designed.** The issue is that `ReentrantReadWriteLock` **intentionally blocks** read locks when a write lock is held.

### How ReentrantReadWriteLock Works

**File:** `IsoChunk.java:5478`
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

**Behavior:**
- **Write lock is exclusive** - only one thread can hold it
- **Read lock blocks if write lock is held** - this is by design
- **Write lock blocks if any read locks are held** - prevents starvation

### The Hang Scenario

```
Thread 1 (SaveChunkThread):
1. Acquires WRITE LOCK on chunk (100, 50)
2. Calls FileOutputStream.write()
3. I/O stalls (NFS timeout, disk contention) - blocks for 30+ seconds
4. Write lock is HELD during entire I/O operation
5. Lock is only released when write() completes (in finally block)

Thread 2 (Main Thread):
1. Player returns to area, needs chunk (100, 50)
2. Calls SafeRead(100, 50)
3. Tries to acquire READ LOCK
4. READ LOCK BLOCKS - waiting for WRITE LOCK to release
5. Main thread HANGS - no frames, no network, server appears frozen
6. Waits indefinitely until SaveChunkThread completes I/O
```

### Why It's Not a Bug in the Lock Code

The lock implementation is **correct**:

**SafeWrite (IsoChunk.java:4322-4348)**
```java
public static void SafeWrite(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    lock.lockForWriting();
    
    try {
        // File I/O - can block for long time
        try (FileOutputStream output = new FileOutputStream(outFile)) {
            output.write(bb.array(), 0, bb.position());
        }
    } finally {
        lock.unlockForWriting();  // ALWAYS releases lock
        releaseLock(lock);
    }
}
```

**SafeRead (IsoChunk.java:4351-4374)**
```java
public static ByteBuffer SafeRead(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    lock.lockForReading();  // BLOCKS HERE if write lock held
    
    try {
        // File I/O
        try (FileInputStream inStream = new FileInputStream(inFile)) {
            inStream.read(bb.array());
        }
    } finally {
        lock.unlockForReading();  // ALWAYS releases lock
        releaseLock(lock);
    }
}
```

**The locks are properly acquired and released in finally blocks.** The problem is:
1. Write lock is held during **blocking I/O** (can take 30+ seconds on slow storage)
2. Read lock **intentionally blocks** waiting for write lock
3. Main thread hangs because it's blocked on read lock acquisition

### Why Vanilla Doesn't See This

**Vanilla:**
- Unloads are rare and immediate
- Very low probability of: save in progress + player returns to same chunk
- Lock contention is extremely rare

**Patched:**
- 19 cells unloading concurrently
- 1,216 chunks potentially being saved
- **HIGH probability** of lock collision
- Lock contention becomes frequent

---

## Solution: Skip Load if Chunk is Being Saved

**Instead of blocking on the lock, check if chunk is being saved BEFORE calling SafeRead().**

### Implementation

**PlayerDownloadServer.java:368** (and line 307)

```java
// BEFORE:
ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
this.sendChunk(reqChunk);

// AFTER:
// Check if chunk is being saved - if so, skip and let client retry
if (ServerMap.ServerCell.chunkLoader.saveThread.hasPendingOrRunningSave(wx, wy)) {
    if (PlayerDownloadServer.this.networkFileDebug) {
        DebugType.NetworkFileDebug.debugln(
            wx + "," + wy + ": deferred - chunk being saved"
        );
    }
    // Don't send chunk now - client will retry after 8-second timeout
    continue;  // Skip to next chunk in request
}

ccr.getByteBuffer(reqChunk);
reqChunk.bb = IsoChunk.SafeRead(wx, wy, reqChunk.bb);
this.sendChunk(reqChunk);
```

### How It Works

1. **Player requests chunk** (100, 50)
2. **Server checks:** Is chunk being saved?
3. **If YES:** Skip this request, don't call SafeRead()
4. **Client doesn't receive chunk**
5. **After 8 seconds:** Client automatically retries
6. **By then:** Save is likely complete, or we skip again
7. **Eventually:** Save completes, chunk is loaded and sent

### Benefits

✅ **Main thread never blocks** - no hang  
✅ **Client auto-retries** - no manual intervention needed  
✅ **Simple, safe fix** - no complex timeout logic  
✅ **No data loss** - chunk is saved, just not loaded immediately  
✅ **Minimal impact** - player sees "loading" for 8-16 seconds longer  

### Drawbacks

⚠️ **Slightly longer load time** - player waits extra 8-16 seconds for chunks being saved  
⚠️ **Not a root cause fix** - doesn't solve slow I/O, just avoids blocking on it  

---

## Alternative: tryLock with Timeout

**More complex, but more graceful:**

### Implementation

**IsoChunk.java:5504** (add new method)
```java
public boolean tryLockForReading(long timeoutMs) {
    try {
        return this.rw.readLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        return false;
    }
}
```

**IsoChunk.java:4351** (modify SafeRead)
```java
public static ByteBuffer SafeRead(int wx, int wy, ByteBuffer bb) throws IOException {
    IsoChunk.ChunkLock lock = acquireLock(wx, wy);
    
    // Try to acquire read lock with 100ms timeout
    if (!lock.tryLockForReading(100)) {
        releaseLock(lock);
        throw new IOException("Chunk " + wx + "," + wy + " is being saved");
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
        // Chunk locked - client will retry after 8s timeout
        ccr.releaseBuffer(reqChunk);
        continue;
    }
    throw e;
}
```

### Pros
- More graceful - tries to acquire lock, gives up if takes too long
- Doesn't require checking save queue state
- Works even if `hasPendingOrRunningSave()` is inaccurate

### Cons
- More complex - requires modifying ChunkLock API
- Requires exception handling in PlayerDownloadServer
- Still has 100ms delay per attempt

---

## Recommendation

**Use the simple "check before read" approach (Option 1):**

1. ✅ **Simpler** - just check `hasPendingOrRunningSave()`
2. ✅ **Faster** - no lock acquisition attempt, no timeout wait
3. ✅ **Safer** - no exception handling needed
4. ✅ **Proven** - client retry mechanism already exists and works

**The tryLock approach is overkill** - we already have a reliable method to check if a chunk is being saved.

---

## Summary

### Question 1: Client Auto-Retry
**YES** - Client automatically retries chunk requests after 8-second timeout. Perfect for our fix!

### Question 2: Indefinite Lock Hang
**Not a bug** - `ReentrantReadWriteLock` intentionally blocks read locks when write lock is held. The issue is that write lock is held during blocking I/O (30+ seconds on slow storage).

### Solution
**Skip load if chunk is being saved:**
- Check `hasPendingOrRunningSave()` before calling `SafeRead()`
- Client retries after 8 seconds
- Main thread never blocks
- Simple, safe, effective
