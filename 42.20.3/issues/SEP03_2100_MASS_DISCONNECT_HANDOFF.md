# Sep 03 21:00 Mass Disconnect Handoff

## Context

This note summarizes the investigation of the Sep 03 around-21:00 mass disconnect on the ApocalipseBR
Project Zomboid Build 42.20.3 server.

Inputs reviewed:

- `C:\Users\ricar\OneDrive\Documents\Github\apocalipsebr-manager\manager\gc-snapshot.log`
- Telemetry pasted at `C:\Users\ricar\.codex\attachments\b9cbdd69-f628-4825-bfb6-774dd19025b2\pasted-text.txt`
- Server log excerpt pasted at `C:\Users\ricar\.codex\attachments\1dccfa5e-13e2-44e8-85b0-c78cdc8b27a8\pasted-text.txt`
- Patched sources under `C:\Users\ricar\OneDrive\Documents\Github\apocalipsebr-zomboid-patches\42.20.3`
- Decompiled 42.20.3 source where needed, especially RakNet and connection handling paths

The working theory at the end of the session is not "packet queue directly caused disconnects".
The stronger, evidence-backed theory is:

```text
main-loop and CPU/MMU starvation caused protocol starvation;
RakNet or clients then removed/lost connections;
queued packets with removed GUIDs were processed afterward and appeared as "connection is null";
large GC/memory release followed the disconnect/unload wave rather than triggering it directly.
```

## Evidence From GC

The ZGC log did not show heap exhaustion or allocation stalls as the primary cause.

Observed around the incident:

- Heap was around 65-67% of the fixed 21.5 GB heap, not full.
- `Allocation Stalls` stayed at `0 0 0 0`.
- Major `GC(698)` ran from about `21:00:09` to `21:00:47`, freeing only about 88 MB.
- Major `GC(705)` ran from about `21:00:51` to `21:01:31`, freeing about 2672 MB.
- Thread count in safepoints dropped around the incident window, consistent with users disconnecting and then world/player state becoming collectible.
- MMU degraded during the same window, with very poor short-window progress, but not a classic stop-the-world heap-full failure.

Interpretation:

```text
GC pressure likely amplified CPU/MMU instability, but the big heap drop looks more like aftermath
from mass disconnect/map unload than the direct initial cause.
```

## Evidence From Telemetry

Telemetry immediately before the disconnect showed severe main-thread latency:

```text
players=55
connections=62
world.avgMs=189.08
world.maxMs=1382.83
gameState.avgMs=139.98
gameState.maxMs=1189.35
stateIsoWorld.avgMs=125.39
stateIsoWorld.maxMs=1150.28
serverMapPost.avgMs=18.66
serverMapPost.maxMs=854.20
unloadQueued=28
unload.avgMs=45.61
unload.maxMs=837.27
load2MainPump.avgMs=12.51
load2MainPump.maxMs=58.51
load2Anchor.avgMs=25.96
load2Anchor.maxMs=58.52
load2BorderRecalc.avgMs=47.93
load2BorderRecalc.maxMs=285.79
load2CellCommitWall.maxMs=726.76
netLoop.high.avgMs=6.96
netLoop.high.maxMs=420.37
normal.processed=840
normal.dropped=301
los.slots=6
los.busyMax=13
los.avgMs=89.94
los.maxMs=607.17
```

Important interpretation:

- `serverMapPost.maxMs=854.20` and `unload.maxMs=837.27` are the strongest local evidence.
- `world.maxMs=1382.83` means at least one server frame was far beyond normal network cadence.
- `normal.dropped=301` means vehicle-physics shedding was already active before the pasted log storm.
- `los.busyMax=13` with `slots=6` is probably metric semantics, not literal slot occupancy. The counter increments at dispatch and decrements after calc, so it includes executor backlog before workers acquire slots.

## Evidence From Server Logs

The pasted server log sequence:

```text
21:00:18 Server is working normal; dropped 12 packets and 0 connections
21:00:18 EngineEntityManager queued off-main entity operation count=923198
21:00:22 Server is too busy; vehicle physics drops enabled; new connections closed
21:00:23 Server is working normal; dropped 45 packets and 0 connections
21:00:23 Server is too busy again
21:00:31-21:00:41 many IsoChunk.removeFromWorld vehicle warnings
21:00:42 many "Received packet type=... connection is null"
21:00:46 Server is working normal; dropped 505 packets and 0 connections
```

Counts from the pasted excerpt:

- `connection is null`: 56
- `IsoChunk.removeFromWorld: vehicle wasn't removed from world`: 174
- `Server is too busy`: 2
- The provided excerpt did not include `connection-lost`, `disconnection-notification`, `Connection disconnect`, or `Connection delayed disconnect` lines.

Important interpretation:

- The log excerpt proves packet drops and stale connection packet handling.
- It does not by itself prove the exact RakNet/client disconnect reason because the RakNet connection-loss lines are missing from the pasted window/category.
- The vehicle warning storm is probably an amplifier during chunk teardown: synchronous logging during a bad main-thread window adds more work and hides useful signal.

## Connection Handling Trace

Source paths checked:

- `src/zombie/core/raknet/UdpEngine.java`
- `src/zombie/network/GameServer.java`
- `decompiled/zombie/core/raknet/RakNetPeerInterface.java`
- `decompiled/zombie/core/raknet/UdpConnection.java`

Relevant mechanics:

```text
UdpEngine thread:
  peer.Receive(...)
  decode(...)
  GameServer.addIncoming(...)

GameServer main thread:
  poll MainLoopNetDataHighPriorityQ
  poll MainLoopPlayerUpdateQ
  poll MainLoopNetDataQ
  mainLoopDealWithNetData(...)
  world update
  ServerMap.postupdate(...)
```

`UdpEngine.decode()` receives asynchronously. User packet id `134` is decoded and queued via
`GameServer.addIncoming(...)`.

`GameServer.addIncoming(...)` copies the packet into one of the main-loop queues:

- `MainLoopPlayerUpdateQ`
- `MainLoopNetDataHighPriorityQ`
- `MainLoopNetDataQ`

Actual gameplay packet processing happens later on the main thread in `mainLoopDealWithNetData(...)`.

`mainLoopDealWithNetData(...)` does:

```java
UdpConnection connection = udpEngine.getActiveConnection(d.connection);
```

If `connectionMap` no longer contains that GUID, it logs:

```text
Received packet type=... connection is null.
```

`UdpEngine.decode()` handles RakNet disconnect/loss events:

- `21`: `disconnection-notification`, then `removeConnection(guid)`
- `22`: `connection-lost`, then `removeConnection(guid)`
- `31`: `remote-disconnection-notification`
- `32`: `remote-connection-lost`

`UdpEngine.removeConnection(...)` removes the connection from `connectionMap` immediately and then queues a
main-loop disconnect or delayed disconnect.

Interpretation:

```text
"connection is null" means queued packet processing found a GUID that had already been removed.
It is aftermath evidence of connection removal, not the direct proof of why the connection was removed.
```

## Why MMU/CPU Degradation Still Matters

More async work can improve average tick time but hurt worst-case latency when the host saturates.

Competing work during the incident likely included:

- main server thread
- UdpEngine thread
- chunk load workers
- ServerLOS workers
- RecalcAll
- ZGC concurrent workers
- Steam/RakNet native work
- off-main entity operations

The old mostly-main-thread model was worse for throughput but more self-throttling: if the main thread was
busy, fewer Java worker pools were competing for cores. The async model lets multiple subsystems make
forward progress in parallel, which is good until the host has no clean CPU headroom left for UdpEngine and
the main loop.

This is why reducing worker pools can improve network survival even if it reduces background throughput.

## Changes Made In This Session

### ServerMap

File: `src/zombie/network/ServerMap.java`

Changed `apocbr.load2.idleMaxMs` default from `100` to `4`.

Reason:

- Existing report documented `4`.
- Code had `100`, which could spend too much idle window on load2 work and worsen packet latency/MMU.

Changed deferred unload overdue behavior:

- Added `apocbr.unload.forceOverdue`, default `false`.
- Added `apocbr.unload.forcedSquaresPerSlice`, default `2048`.
- Overdue cells no longer expand `cellsToTouch` or `maxSlices`.
- Overdue cells no longer run with `Integer.MAX_VALUE` squares by default.
- Deadline checks now apply even when forced mode is enabled.

Reason:

- Telemetry showed `unload.maxMs=837.27`.
- Previous deadline logic could convert backlog into full-cell main-thread teardown.
- New behavior fails slow by allowing unload backlog instead of spiking a server tick.

### IsoChunk

File: `src/zombie/iso/IsoChunk.java`

Rate-limited:

```text
IsoChunk.removeFromWorld: vehicle wasn't removed from world id=...
```

Added:

- `apocbr.unload.vehicleWarnIntervalMs`, default `5000`
- suppressed warning count emitted once per interval

Reason:

- Pasted logs had 174 vehicle cleanup warnings in the incident excerpt.
- Synchronous warning spam during chunk teardown amplifies the main-thread stall and obscures useful logs.

### ServerChunkLoader

File: `src/zombie/network/ServerChunkLoader.java`

Changed defaults:

- `apocbr.loadChunkWorkers`: `6` -> `3`
- `apocbr.loadGridSquareThreadCacheSize`: `10000` -> `2048`
- Added `apocbr.recalcThreadPriority`, default `Thread.NORM_PRIORITY`
- `RecalcAll` no longer runs at hardcoded priority `10`

Reason:

- Preserve CPU/MMU headroom for UdpEngine and the main loop on an 8-core host.
- Smaller per-thread grid-square caches reduce old-gen live set.

### ServerLOS

File: `src/zombie/network/ServerLOS.java`

Changed:

- hardcoded `LOS_WORKER_THREADS = 6`
- to `apocbr.losWorkerThreads`, default `4`

Reason:

- Reduce CPU contention during LOS-heavy and GC-heavy windows.

### GameServer

File: `src/zombie/network/GameServer.java`

Changed:

- Fixed comment saying timeout was widened to 45s; current default is 12s.

Already-relevant existing logic:

- Packet queues are bounded.
- Drop-oldest recycles packet buffers.
- Idle-window load/unload only runs when `apocBrHasIncomingPacketBacklog()` is false.

### ApocBRTelemetrySampler

File: `src/zombie/ApocBRTelemetrySampler.java`

Changed queue-depth sampling to use explicit `GameServer.getApocBRQueueDepth(...)` counters first, falling
back to reflection only for older partial patch sets.

Reason:

- Avoid `ConcurrentLinkedQueue.size()` scanning in telemetry.

### Reports Updated

Files:

- `issues/LOAD2_CROSS_TICK_REPORT.md`
- `issues/UDP_ALLOCATION_OPTIMIZATION_REPORT.md`

Updates:

- Added Sep 03 incident summary.
- Added new unload/worker tuning properties.
- Documented the evidence and operational intent.

## Current Operational Recommendation

Use these defaults for the next live run:

```text
-Dapocbr.raknetTimeoutMs=12000
-Dapocbr.raknetUnreliableTimeoutMs=2000
-Dapocbr.raknetThreadPriority=true
-Dapocbr.loadChunkWorkers=3
-Dapocbr.loadGridSquareThreadCacheSize=2048
-Dapocbr.recalcThreadPriority=5
-Dapocbr.losWorkerThreads=4
-Dapocbr.load2.idleMaxMs=4
-Dapocbr.unload.forceOverdue=false
-Dapocbr.unload.maxMsPerTick=8
-Dapocbr.unload.maxCellsPerTick=4
-Dapocbr.unload.slicesPerTick=8
-Dapocbr.unload.squaresPerSlice=1024
-Dapocbr.unload.idleMaxMs=4
-Dapocbr.unload.vehicleWarnIntervalMs=5000
```

Consider testing a higher RakNet timeout if another incident happens without obvious crash/OOM:

```text
-Dapocbr.raknetTimeoutMs=20000
```

or:

```text
-Dapocbr.raknetTimeoutMs=30000
```

That is a survival tradeoff: players may rubber-band longer during a severe stall, but a transient server
seizure is less likely to become a whole-server disconnect wave.

For ZGC worker contention on an 8-core host, consider controlled testing with:

```text
-XX:ConcGCThreads=2
-XX:ParallelGCThreads=4
```

Do not treat this as proven better without live telemetry; it trades GC throughput for CPU predictability.

## Open Questions

The missing proof is the exact disconnect initiator.

Need logs or telemetry for:

- RakNet packet id `21` count: `disconnection-notification`
- RakNet packet id `22` count: `connection-lost`
- RakNet packet id `31` count: `remote-disconnection-notification`
- RakNet packet id `32` count: `remote-connection-lost`
- max time between successful `UdpEngine.Receive()` calls
- max time between `UdpEngine.decode()` calls
- max/avg age of packets when `mainLoopDealWithNetData()` processes them
- age of packets that hit `connection is null`
- queue max age for high/player/normal queues, not only queue depth

These will distinguish:

```text
UdpEngine thread starved
main thread starved while UdpEngine kept receiving
client-side timeout due to missing server responses
server-side RakNet timeout/loss
explicit disconnect path unrelated to timeout
```

## Recommended Next Patch

Add UdpEngine and packet-age telemetry before making more speculative changes.

Useful counters:

- `raknetDisconnectNotification`
- `raknetConnectionLost`
- `raknetRemoteDisconnectNotification`
- `raknetRemoteConnectionLost`
- `udpReceiveGapMaxMs`
- `udpDecodeGapMaxMs`
- `netHighPacketAgeMaxMs`
- `netPlayerPacketAgeMaxMs`
- `netNormalPacketAgeMaxMs`
- `connectionNullPacketAgeMaxMs`
- `unknownConnectionPacketCount`

Best locations:

- `UdpEngine.threadRun()` / `Receive()` for receive/decode gaps.
- `UdpEngine.decode()` cases `21`, `22`, `31`, `32`, and unknown connection handling.
- `GameServer.mainLoopDealWithNetData()` for packet processing age and connection-null packet age.
- Existing `ApocBRServerTelemetry` JSON snapshot for exposing the counters.

## Verification Done

Ran:

```text
git diff --check
```

Result:

- Passed.
- Only normal LF-to-CRLF warnings were emitted by Git.

Full compile/package was not run in this session.

