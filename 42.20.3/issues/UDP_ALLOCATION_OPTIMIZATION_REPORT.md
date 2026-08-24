# UDP engine allocation and disconnect-resistance report

## Context

Symptom reported: on a loaded server all players are disconnected simultaneously, without an
`OutOfMemoryError`, without a main-thread crash, and without any exception in the server log. The
server process survives and keeps ticking. The host runs under memory pressure with swap enabled.

Telemetry snapshot at 51 players, 60 connections, 2778 zombies, 285 ticks:

- `world.avgMs 96.06`, `world.maxMs 264.59` against a 100 ms budget
- `tickSections.throttleSleep.avgMs 7.75`, so the loop still had real slack
- `tickSections.gameState.maxMs 168.65`, `serverMapPost.maxMs 159.17`, `unload.maxMs 148.05`
- `queues` at sample time: `high 24`, `player 6`, `normal 1`
- `netLoop` packet counts: `high 12535`, `player 3234`, `normal 886`

Thread CPU accounting from a manual thread dump, 7163.91 s uptime, JDK 25 generational ZGC:

| Thread | CPU | Share of one core |
| --- | --- | --- |
| `ZWorkerOld#0` | 1,760,442 ms | 24.6 % |
| `ZWorkerOld#1` | 1,457,489 ms | 20.3 % |
| old generation total | 3,217,932 ms | 44.9 % |
| `ZWorkerYoung#0` + `#1` | 209,664 ms | 2.9 % |
| `main` | 2,781,242 ms | 38.8 % |

Two conclusions follow directly from that table. Old-generation GC consumed more CPU than the entire
game loop. And the young/old asymmetry shows the problem is live-set size and cycle frequency, not
allocation rate: young generation cost tracks allocation and it was nearly idle.

The tick telemetry independently rules out the game loop as the source of the stall. Worst measured
tick was 264.59 ms, two orders of magnitude below the disconnect threshold. Whatever produces the
gap is below the JVM: the allocator or the kernel.

## Failure model

```text
main thread tick lengthens (ZGC old-gen CPU starvation, or swap page faults)
  -> main stops draining MainLoopPlayerUpdateQ / MainLoopNetDataHighPriorityQ / MainLoopNetDataQ
  -> nothing calls ZomboidNetDataPool.discard(), so the pool drains
  -> UdpEngine thread keeps receiving at full speed and now allocates per packet
  -> allocation rate spikes exactly while the collector is least able to keep up
  -> ZGC allocation stall: the allocating thread parks in the page allocator
  -> the parked thread is the UdpEngine thread itself
  -> Java side emits nothing for longer than the RakNet timeout
  -> RakNet expires connections, decode() case 22 (ID_CONNECTION_LOST)
  -> GameServer.addDisconnect for every connection in the same second
```

The self-reinforcing property is the important part: draining the queues and refilling the pool are
the same main-thread operation. A main-thread stall does not merely delay packets, it converts the
whole receive path from pooled to allocating at the worst possible moment.

An allocation stall is not an error. ZGC parks the thread and resumes it later. Nothing is thrown and
nothing is logged without `-Xlog:gc*`. This is why the failure is silent.

## Root causes found

### 1. RakNet timeout set to 2000 ms

`zombie/core/raknet/UdpEngine.java` called `this.peer.SetTimeoutTime(2000)` after `Startup()`.

Vanilla never calls `SetTimeoutTime` at all. Both natives are declared and unused:

- `decompiled/zombie/core/raknet/RakNetPeerInterface.java:92` `public native void SetTimeoutTime(int time)`
- `decompiled/zombie/core/raknet/RakNetPeerInterface.java:98` `public native void SetUnreliableTimeout(int timeout)`

RakNet expires every connection once the Java side stops emitting traffic for the timeout period, so
any single stall past the threshold drops the whole server in the same second. At 2000 ms this is
five times tighter than RakNet's own compiled-in default. A ZGC old-generation cycle walking a
multi-gigabyte live set that is partly paged out exceeds 2 seconds routinely, which made this value
effectively a scheduled outage.

### 2. Packet pool starts empty and starves under load

`decompiled/zombie/network/ZomboidNetDataPool.java`

- The pool starts empty and is only refilled by `discard()`, which runs on the main thread after a
  packet has been processed. A stalled main thread therefore guarantees an empty pool.
- `getLong(int)` at line 22 always allocated, and `discard()` at line 15 only accepted buffers of
  capacity 2048. Every packet larger than 2 KB was pure garbage, every time.

### 3. Three packet-pool leaks, all worst under load

- `GameServer.addIncoming`, unknown packet type: the buffer was dropped without `discard()`.
- `GameServer.mainLoopDealWithNetData`, `connection == null`: early `return` without `discard()`.
  Every packet still queued for a player who just dropped leaked its buffer, so a mass disconnect
  permanently drained the pool at the exact moment recovery mattered.
- `GameServer` main loop, vehicle-physics overload valve: the `break` at the 70 ms guard abandoned the
  remaining buffers without discarding. This path fires only when the tick is already over budget.

Each leak starves the pool, which forces the UdpEngine thread to allocate, which is the one thread
that must never park.

### 4. Unbounded main-loop queues

`MainLoopPlayerUpdateQ`, `MainLoopNetDataHighPriorityQ` and `MainLoopNetDataQ` are unbounded
`ConcurrentLinkedQueue` instances. The UdpEngine thread keeps enqueuing for the entire duration of a
stall, so raising the timeout without bounding the queues would trade a mass disconnect for an
`OutOfMemoryError`.

### 5. Boxing on the receive path

`UdpEngine.decode()` case 134, the normal game packet path, resolved the connection with
`connectionMap.get(guidx)` where `connectionMap` is `Map<Long, UdpConnection>` and `guidx` is a
primitive `long`. RakNet GUIDs are large and far outside the `Long.valueOf` cache, so every single
packet allocated a fresh `Long` on the UdpEngine thread. This alone defeated any attempt to make the
receive path allocation free.

### 6. UdpEngine thread priority

`decompiled/zombie/core/raknet/UdpEngine.java:82` creates the thread without setting a priority, so
it runs at `NORM_PRIORITY`. `ServerChunkLoader`'s `RecalcAll` thread runs at priority 10. Under CPU
steal the thread holding every connection open loses the scheduler race to chunk recalculation.

### 7. Per-packet garbage in the rate limiter

`decompiled/zombie/network/PacketsCache.java:51-90`, `isLimitExceeded`, runs once per received packet
per connection on the main thread.

- Fields at lines 14-16 used `HashMap<PacketType, List<Long>>` over `LinkedList<Long>`. Every
  recorded packet allocated a boxed `Long` (epoch millis are never shared) plus a list node.
- The method iterated every packet type's list on every call and ran two `removeIf` passes over each.
  Both lambdas capture `currentTime`, so they cannot be cached as singletons: two more allocations
  per list per call.

With roughly 50 distinct packet types seen per connection this is on the order of 100 short-lived
objects per packet, multiplied by packet rate and by player count.

## Changes applied

### `src/zombie/core/raknet/UdpEngine.java`

- `SetTimeoutTime` 2000 ms to 12000 ms, configurable.
- `SetUnreliableTimeout(2000)` added, so stale unreliable packets are dropped inside RakNet instead of
  being copied onto the heap only to be discarded by the main loop moments later.
- Both calls wrapped in `try/catch`. `SetUnreliableTimeout` has never been exercised by vanilla, and a
  missing JNI binding must not prevent server startup.
- UdpEngine thread raised to `Thread.MAX_PRIORITY`.
- `ZomboidNetDataPool.prewarm()` invoked before the listener starts.
- New `lookupConnectionNoAlloc(long)`: scans a primitive `long[256]` mirror of `connectionArray`
  instead of boxing. Falls back to `connectionMap` on a miss, so behaviour is identical to vanilla and
  boxing is paid only in the rare miss, never on the steady-state hot path.

### `src/zombie/network/ZomboidNetDataPool.java` (new)

- `prewarm()` pre-allocates the small pool at startup, on the calling thread, before any client
  connects.
- Small pool capped so a burst cannot turn the pool itself into the leak.
- Power-of-two size classes from 4 KB to 1 MB for the large path, so packets over 2 KB are recycled.
- Hit and miss counters for verification.

### `src/zombie/network/GameServer.java`

- All three pool leaks now `discard()`.
- All three main-loop queues bounded via a shared `apocBrEnqueue` helper implementing drop-oldest with
  immediate recycle into the pool.
- Depths tracked with `AtomicInteger` rather than `ConcurrentLinkedQueue.size()`, which is O(n) and
  cannot be called per packet.
- Counters exposed: `getApocBRPlayerQueueDropped`, `getApocBRHighQueueDropped`,
  `getApocBRNormalQueueDropped`, `getApocBRPacketsReclaimed`, `getApocBRQueueDepth`.

### `src/zombie/network/PacketsCache.java` (new)

- `isLimitExceeded` rewritten around a per-type primitive `long` ring buffer indexed by enum ordinal.
  No boxing, no lambdas.
- Pruning touches only the type being queried. This is observably equivalent because the return value
  depends solely on that one type's window size; stale entries parked in other types' windows never
  influenced the decision.
- Vanilla's client/server asymmetry is preserved exactly: the client records before the limit check,
  the server records only after passing it.
- `isHashEquals` moved from `HashMap<PacketType, Integer>` to `int[]` plus a presence flag, preserving
  the original contract that an absent previous value returns `false`.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `apocbr.raknetTimeoutMs` | 12000 | RakNet connection timeout |
| `apocbr.raknetUnreliableTimeoutMs` | 2000 | Native drop age for unreliable packets |
| `apocbr.raknetThreadPriority` | enabled | Set `false` to leave UdpEngine at normal priority |
| `apocbr.netDataPrewarm` | 26624 | Small buffers pre-allocated at startup |
| `apocbr.netDataMaxPooled` | 32768 | Small pool ceiling |
| `apocbr.netDataMaxPooledLarge` | 64 | Per size class ceiling for the large pool |
| `apocbr.playerUpdateQueueMaxDepth` | 4096 | Position update queue cap |
| `apocbr.highPriorityQueueMaxDepth` | 16384 | Gameplay packet queue cap |
| `apocbr.normalQueueMaxDepth` | 4096 | Vehicle physics queue cap |

## Sizing rationale

Measured packet rate from the telemetry snapshot:

```text
16,655 packets over 285 ticks at 96.06 ms  = 27.38 s
                                     rate  = 608 packets/sec total
                                per player = 11.9 packets/sec at 51 players
```

An earlier sizing proposal assumed 300 packets/sec per player, which would imply pre-allocating
150,000 buffers, or 307 MB, to cover a 10 second stall. That is 25 times the measured rate, and the
approach is wrong regardless. Pooled buffers are permanently live, so 307 MB of them would be marked
on every old-generation cycle, adding work to the exact collector that is already the bottleneck.

Pre-allocation does not reduce the memory required during a stall. The buffers still exist, they just
migrate from pool to queue and back. What pre-allocation changes is who allocates and when: the main
thread at startup instead of the UdpEngine thread mid-stall. The queue caps, not the burst estimate,
are what bound total memory.

Hence the rule used here:

```text
prewarm >= sum of queue caps
        =  4096 + 16384 + 4096 = 24576
        -> 26624 chosen, about 54 MB
in-flight ceiling = 24576 * 2 KB = about 50 MB
```

Because the queues can never hold more buffers than the pool started with, the pool cannot empty, so
the UdpEngine thread provably never allocates on the receive path regardless of stall duration. At the
measured rate the caps correspond to roughly 40 seconds of buffering.

## Timeout rationale

RakNet timeouts are evaluated independently by each side. Clients run the vanilla default of
10000 ms, and there is no `42.20.3-client` patch, so the client value cannot be assumed to change.

Any server timeout above the client's is dead weight: the client will already have given up and
returned the player to the menu, and the server is left holding a dead connection along with its
per-connection memory and player slot. Sitting just above the client threshold means the server is
never the side that pulls the trigger, which was the 2000 ms defect, without babysitting corpses.

A stall long enough to breach 12 seconds produces an unplayable client anyway. A clean disconnect is
preferable to a frozen session.

## Degradation order under stall

The shed order is now explicit rather than incidental:

1. Stale position updates. Free to drop, they are already obsolete when the main thread wakes.
2. Vehicle physics. Already the vanilla overload valve.
3. Gameplay packets, last resort only, logged at most once per 5 seconds with a running total.

Reaching stage 3 loses game state. It is preferred over an `OutOfMemoryError`, which loses the server.
Set `apocbr.highPriorityQueueMaxDepth` very high to disable that stage if the trade is unacceptable.

## Scope limits

State plainly what this work does and does not address.

Addressed: the silent mass-disconnect mechanism, allocation on the UdpEngine thread, three pool
leaks, unbounded in-flight packet memory, and per-packet garbage in the rate limiter.

Not addressed: the `OutOfMemoryError` observed at `-Xmx14g` with about 50 players. That is a live-set
problem. Per-connection structures were measured and total roughly 64 MB at 60 connections:

| Structure | Per connection | At 60 connections |
| --- | --- | --- |
| `UdpConnection.bb`, `decompiled/zombie/core/raknet/UdpConnection.java:41` | 1 MB | 60 MB |
| 253 packet handler instances, one per type, per connection | about 20 KB | about 1.2 MB |
| 3 `HashMap`s keyed by packet type | about 36 KB | about 2 MB |

64 MB is not where a 12 to 14 GB live set lives. The 50 MB packet ceiling introduced here is noise
against it. The live set must be dominated by world state: roughly 445 loaded chunks and about 28,000
grid squares at the sampled player count.

Related observations not acted on:

- `decompiled/zombie/network/PlayerDownloadServer.java:42` also allocates a 1 MB `ByteBuffer` per
  worker. Worker threads grew from 8 to 27 between two dumps 95 minutes apart, all parked on
  `LinkedBlockingQueue.take()`, which suggests they are not reaped.
- `RequestDataPacket.largeFileBb` is a static 50 MB buffer, allocated on first use and never freed.

## Open questions

Two items would confirm or refute the model above. Neither has been observed yet.

1. Which RakNet event arrives during an incident. In the `ConnectionManager` log,
   `connection-lost` (id 22) means the server timed out the client, and the timeout change addresses
   it directly. `disconnection-notification` (id 21) means the client left on its own, in which case a
   server-side timeout change does not help and a client patch would be required.
2. A heap histogram under load, `jcmd <pid> GC.class_histogram`, at the player count where the OOM
   occurs. This is the only remaining evidence needed to locate the live set.

An unresolved uncertainty worth recording: RakNet normally runs an internal native update thread that
sends ACKs and pings independently of Java. If PZ's native build works that way, a pure Java stall
would not expire connections and the model above is incomplete. If PZ built RakNet user-threaded, so
that `Receive()` drives the update loop, the model holds exactly. This cannot be determined from the
Java source and was not verified.

## Verification

- Startup must log `[ApocBR] RakNet timeout=12000ms unreliableTimeout=2000ms`. Absence means the JNI
  call failed and execution fell into the catch block.
- `getApocBRHighQueueDropped()` should remain 0 in normal operation. A non-zero value means the main
  thread stalled long enough to saturate the gameplay queue.
- `-Xlog:gc*` should be enabled so allocation stalls are recorded rather than inferred. Look for
  `Allocation Stall` entries.
- Sources in this report have not been compiled. Run
  `./patchApocalipseBr.sh --pz-dir /opt/pzserver --dry-run` before deploying.
