# Server simulation, LOS and zombie transmission

These ports target the original Project Zomboid 42.22.2 JAR and are enabled by
default, alongside the [first five optimization ports](server-optimizations.md).
Pass `-D<property>=false` at JVM startup to disable an individual module.

| Port | JVM property | Configuration |
| --- | --- | --- |
| Server LOS workers and zombie perception | `apocbr.optimization.serverLos.enabled` | `apocbr.los.workerThreads` defaults to vanilla's four cache slots. `apocbr.los.queueCapacity` defaults to 256. `apocbr.los.intervalMs` defaults to 2000 with a 250 ms minimum. |
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
