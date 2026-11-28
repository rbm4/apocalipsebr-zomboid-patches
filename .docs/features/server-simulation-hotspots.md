# Server simulation, LOS and zombie transmission

These ports target the original Project Zomboid 42.22.2 JAR and are enabled by
default, alongside the [first five optimization ports](server-optimizations.md).
Pass `-D<property>=false` at JVM startup to disable an individual module.

| Port | JVM property | Configuration |
| --- | --- | --- |
| Server LOS workers and zombie perception | `apocbr.optimization.serverLos.enabled` | `apocbr.los.workerThreads` defaults to vanilla's four cache slots. `apocbr.los.queueCapacity` defaults to 256. `apocbr.los.intervalMs` defaults to 2000 with a 250 ms minimum. |
| Dedicated animal perception | `apocbr.optimization.animalPerception.enabled` | `apocbr.animalLos.intervalTicks` defaults to 10. Set it to 1 for spatial filtering on every scheduled update. |
| Entity catch-up and scheduling indexes | `apocbr.optimization.entitySimulation.enabled` | `apocbr.metaSimulationInterval` defaults to 10 simulation ticks, with a minimum of one. |
| Global engine entity removal index | `apocbr.optimization.entityRemoval.enabled` | Index state follows the original `EngineEntityManager` instance. |
| Async zombie transmission | `apocbr.optimization.zombieAsyncSend.enabled` | `apocbr.zombiePacketWorkers` defaults to six, bounded to 1–32. `apocbr.zombieSendCapacity` defaults to 64 outstanding batches, bounded to 1–256. |

## Server LOS

The worker hooks replace the dedicated server's single LOS thread with a
bounded pool. The server captures the player's light origin before enqueueing
a job. Workers calculate the vanilla cached line tests using leased slots.
The server-side visibility bit depends on those tests and the availability of
the light square and target chunk. Keeping room exploration on the owner
thread removes the need for worker-side lighting writes or a temporary
`IsoPlayer.players[0]` assignment.

`ServerLOS.suspend()` stops admission, cancels queued and running jobs, and
waits for running readers before returning to the map loader. Workers check
cancellation between rows. Running readers release their cache slots before
the barrier opens. Results from a prior map, another floor, or excessive
movement are discarded. `resume()` permits fresh jobs, player removal discards
that player's state, and `ServerMap.QueuedQuit()` closes the pool.

The dedicated-server `IsoPlayer.updateLOS()` hook follows the legacy
zombie-authority path and keeps the original zombie-list order. It shares a
tile index across completions with a tracked vanilla list, invalidating it when
membership, ordering, or a zombie's tile changes. Plain lists and unusual
coordinates use an ordered list snapshot. Client and single-player LOS use
their original entry points.

## Animal perception

The original dedicated server targets 10 simulation ticks per second. The agent
uses the native moving-object scheduler frame and the animal's ID to stagger
periodic LOS scans over ten ticks, about one second at target rate. Reaction to
a newly visible target can wait until the animal's next scan. LOS-driven
stress, acceptance and tracking XP checks also run at that cadence. The
alert cooldown still ages on each scheduled perception opportunity. Direct
LOS calls and direct spotting or flee calls keep their native cadence.

A cell-owned index adapts the legacy animal perception grid to the original
JAR. It keeps every human candidate and checks zombies within ten tiles only
for animals that flee zombies. Nearby candidates reach the vanilla LOS loop in
cell iteration order. The hook retains the native floor, square, visibility,
alert and flee checks. Each scan resets the spotted target once before
processing candidates.

The native cell set remains authoritative. Structural changes discard its
cached index, including removals through iterators and cell clear. The first
zombie query in each scheduler pass refreshes bucket membership. Owner-thread
zombie bucket changes invalidate that refresh for any later query in the same
pass. Scheduler exit releases its temporary scope, including when a callback
throws. The native cell owns the index.

Run the packaged original-JAR fixture with
`./tools/Test-OptimizationAgent.ps1`. Live herd behavior and production
slow-tick costs still need validation.

## Entity catch-up and indexing

Vanilla `EntitySimulation` still computes elapsed 100 ms ticks.
`GameEntityManager.Update()` consumes a positive tick count in one dedicated
server simulation pass. Loaded entities receive the frame's elapsed count.
Meta entities receive the ticks owed at the phase selected by their network ID.
Crafting and drying use effective game seconds. Furnace fuel and slots, energy
decay, petrol evaporation, and rain collection use the corresponding elapsed
ticks or seconds. Mashing joins the simulation pass and still uses absolute
world age. The legacy Logistics pass had no effect on entity state and is
skipped during batched simulation. These changes use the 42.22.2 catch-up
semantics, including one pass through callbacks when a frame spans several
ticks.

Each active simulation bucket maintains an ordered phase view through native
bucket append, removal and membership updates. Exact meta objects refresh
their phase on load and reset. Drying detachment checks and unclassified entity
types retain eligibility checks. The using-player index tracks native
`usingPlayer` writes and iterates live successors so a callback can activate
or remove a later entry in the same update. The world reset clears both
indexes.

The independent engine removal module keeps an identity-to-position index
for the private unordered engine array. Native add/remove calls continue to
run their membership, delayed-operation, and listener logic. An indexed
removal uses the array's own swap removal and updates the moved tail's index.

## Async zombie sends

The hook keeps the vanilla authorization update and incoming/deleted-list
drain at the start of `NetworkZombiePacker.postupdate()`. The agent takes over
outgoing selection and submission on the simulation owner. It admits one
batch per exact connection, rotates admission across recipients, keeps
deletions and requests pending under backpressure, and freezes the native
packet header and payload before the worker is scheduled. The worker receives
bytes and a captured peer/GUID, and calls the original JAR's `SendRaw` with
its own direct buffer. The worker never prepares a native zombie packet.

The owner drains completions, accounts for attempted packets, and restores
still-current requests and retained deletions after failure. Disconnect
invalidates the connection's lane. `UdpEngine.Shutdown()` waits for the
workers before the native peer is shut down. Timers advance at admission and
a captured snapshot can arrive later than a synchronous send. This adapts
transmission to the original JAR's packet preparation and ownership APIs.
The separate legacy all-zombie per-frame bookkeeping and demand-driven packet
field cache remain outside this port.

## On-demand entity bucket removal positions

The fallback index defaults on and runs on the dedicated-server owner through
the existing entity simulation hooks. Disable it with
`-Dapocbr.optimization.entityBucketRemoval.enabled=false`. The world’s native bucket arrays remain authoritative and the original
remove-index tail swap, membership bits and listener order remain in force.
Buckets already served by the simulation or using-player index reuse those
positions.

A fallback bucket stays on the native scan path for its first successful
removal at a size of at least 256. Further removal demand can build a
primitive-valued identity index. Appends and native swaps maintain its
positions. The index releases at fewer than 128 entries, when an existing
simulation index takes ownership, or on world reset. Invalid positions fall
back to the native search. The counters `entities.bucketRemoval.indexed`
and `entities.bucketRemoval.indexBuilds` report actual work. See the
[original-JAR bucket A/B](../validation/server-integration.md#entity-bucket-removal-ab)
for the focused probes and comparison.

The comparison used the same packaged agent, original 42.22.2 JAR and
30-position fixture with the switch off and on. Both completed real streaming,
save drain, reload, the native bucket ordering oracle and graceful shutdown.
The following timings sum the five warm cycles unless otherwise marked.

| Metric | Index off | Index on |
| --- | ---: | ---: |
| Native bucket comparisons | 81,613,225 | 38,905 |
| Native bucket search and removal time | 22.928 ms | 0.083 ms |
| Bucket append/remove time, including searches and index builds | 50.466 ms | 39.653 ms |
| Bucket fan-out wall time | 80.118 ms | 66.731 ms |
| Object entity ID work within unregistration | 4.925 ms | 4.918 ms |
| Median cell unload per warm cycle | 197.867 ms | 197.774 ms |

Vanilla's `NonMetaEntities` (`CustomBucket`, index 2) accounted for all of
the bucket comparisons in both arms. The similarly sized `IsoObjectBucket`
already used its using-player positions. With the switch on, the probe saw
five index builds taking 6.373 ms inside the mutation timestamps. Five
first-removal scans and 635 small-bucket scans remained. No stale-index
fallbacks were recorded. The whole-unload medians were effectively equal.

Raw A/B artifacts live under `target/server-integration` in
`run-16766703884949405965` (off) and `run-17707118796149724685` (on).
The fixture is playerless. Retained index memory and connected-player
workloads were outside the comparison.

## Measurement and verification

Telemetry includes LOS queue, rejection, stale-result and candidate counts,
LOS worker/completion duration and suspension wait, collapsed catch-up ticks,
skipped bucket members, using-player candidates, indexed removals, async
backpressure, submitted bytes, and snapshot, queue and transport duration.

```powershell
$env:JAVA_HOME = (Resolve-Path ./jdk).Path
mvn "-Djar.forceCreation=true" package
./tools/Test-OptimizationAgent.ps1 -LegacyHotspots
```

The forked fixture uses the production shaded agent and original game classes.
It checks hook installation with the flags omitted and confirms that explicit
opt-outs suppress those hooks. With defaults, it exercises LOS pause and
resume, spatial candidate invalidation, entity catch-up and index maintenance,
and async send backpressure, retry, and shutdown. The original JAR SHA-256 is
`3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
Evidence and logs stay under `target/optimization-validation/run-*`. The
fixture uses a controlled transport in place of a live native peer. Check
connected-client packet freshness and ordering, loaded/unloaded entity
transitions under contention, and LOS tail latency with a populated world
before attributing production improvements to the ports.

[Migration inventory and historical measurements](../reports/2026-11-21-legacy-optimization-migration-status.md)
