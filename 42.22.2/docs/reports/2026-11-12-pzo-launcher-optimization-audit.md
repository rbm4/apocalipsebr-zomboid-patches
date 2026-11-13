# PZO-Launcher optimization and threading source audit

Date: 2026-11-12. Scope: static source investigation, not a performance or runtime certification.

PZO checkout: `Z:/git/Github/PZO-Launcher`, commit
`2a3c707bab7e9038e71df83219aa4baed8d67337` (v2.0.1 merge, 2026-11-12).
ApocalipseBR checkout: `3e334e0fa5d637459a2b70c4b3fbf3d49eaa0a17`, target server
42.22.2. The latest separate client patch directory found was 42.21.0-client;
client ideas below require a fresh comparison against the intended client build.
PZO's companion Workshop Lua implementation and released binary/source parity
were not audited. No optimizer was installed, launched or deployed.

## Principal finding

PZO contains substantive client rendering, animation and loading changes. Its
useful threading model is narrowly divided work with explicit completion points,
not concurrent execution of whole zombie/world updates. Its own
`PZOMultiCoreEngine` explicitly leaves AI, physics and world updates on the game
thread. Several dedicated-server README claims are substantially stronger than
the corresponding implementation.

Bytecode injection reduces the amount of vanilla source carried in a patch. It
does not remove shared mutable state, make Lua thread-safe, or create parallelism
by itself. The same execution boundaries can be implemented in source overrides.

## Implementation map

PZO paths in this report resolve to the inspected adjacent checkout.

| Area | Implementation observed | Applicability and limit |
| --- | --- | --- |
| Zombie animation | [BoneBatch](../../../../PZO-Launcher/src/com/pzoptimizer/BoneBatch.java), [AnimationThreadingPatches](../../../../PZO-Launcher/src/com/pzoptimizer/AnimationThreadingPatches.java) | Strong client candidate. Defer selected bone/model/skin calculations, join before proceeding beyond scheduler postupdate. Does not parallelize AI. |
| Action variables | [ActionParallel](../../../../PZO-Launcher/src/com/pzoptimizer/ActionParallel.java), [CallbackPurity](../../../../PZO-Launcher/src/com/pzoptimizer/CallbackPurity.java) | Single-player only: explicitly excludes both multiplayer clients and servers. Selected variable reads run on workers; state transitions and continuation stay on the game thread. |
| Worker infrastructure | [FrameWorkers](../../../../PZO-Launcher/src/com/pzoptimizer/FrameWorkers.java), [PerThreadScratch](../../../../PZO-Launcher/src/com/pzoptimizer/PerThreadScratch.java) | Game thread participates in a batch, then waits for completion. Worker-local scratch replaces selected shared animation temporaries and matrix pools. |
| Depth-map startup | [DepthMapParallel](../../../../PZO-Launcher/src/com/pzoptimizer/DepthMapParallel.java) | Decode/build tileset outside the shared map lock; lock only lookup and publication. Uses existing file tasks. |
| Texture loading | [TextureDownscaleGovernor](../../../../PZO-Launcher/src/com/pzoptimizer/TextureDownscaleGovernor.java) | Moves CPU mipmap/downscale work after image decode onto file workers. GL upload remains a separate render-thread concern. |
| Streamer latency | [StreamerWake](../../../../PZO-Launcher/src/com/pzoptimizer/StreamerWake.java) | Replaces fixed sleep with park/unpark and signals on enqueue. Improves response of an existing background thread. |
| Claimed parallel chunk loader | [MultiCoreChunkStreamer](../../../../PZO-Launcher/src/com/pzoptimizer/multicore/MultiCoreChunkStreamer.java) | Worker pool and loading methods exist, but private dispatchChunkTask has no caller in inspected source. Active queue override only enqueues and wakes WorldStreamer. |
| Native pathfinding throughput | [PathfindGovernor](../../../../PZO-Launcher/src/com/pzoptimizer/PathfindGovernor.java) | Uses the existing single native-pathfinder thread: time-budget requests, coalesce square updates, throttle vehicle changes. Potential client/server candidate when that pathfinder is active and hooks install. |
| Render batching | [RenderBatchPatches](../../../../PZO-Launcher/src/com/pzoptimizer/RenderBatchPatches.java), [WeatherTileBatcher](../../../../PZO-Launcher/src/com/pzoptimizer/WeatherTileBatcher.java) | Larger vertex/index batches; construct weather particles once and repeat using cell offsets. Reduces repeated CPU work and driver calls. |
| Sprite buffer uploads | [PersistentSpriteVbo](../../../../PZO-Launcher/src/com/pzoptimizer/PersistentSpriteVbo.java) | Persistent/coherent mapping with GPU fences; extension-dependent fallback. Client only. |
| Chunk render caching | [OcclusionReuse](../../../../PZO-Launcher/src/com/pzoptimizer/OcclusionReuse.java), [CutawayRebakeFilter](../../../../PZO-Launcher/src/com/pzoptimizer/CutawayRebakeFilter.java), [ChunkBakeBudget](../../../../PZO-Launcher/src/com/pzoptimizer/ChunkBakeBudget.java) | Avoid redundant reconstruction/rebakes or spread work between frames. Correct invalidation and visual latency matter. |
| Foliage/render detail | [TreeBake](../../../../PZO-Launcher/src/com/pzoptimizer/TreeBake.java), [TranslucentBake](../../../../PZO-Launcher/src/com/pzoptimizer/TranslucentBake.java), [ZombieLodGovernor](../../../../PZO-Launcher/src/com/pzoptimizer/ZombieLodGovernor.java) | Detail/baking policy changes; TreeBake defaults off. Adaptive model/blend counts trade visual detail for time. |
| Simulation cadence | [UnseenZombieSimGovernor](../../../../PZO-Launcher/src/com/pzoptimizer/UnseenZombieSimGovernor.java) | Caps scheduler FPS input for qualifying unseen rendered zombies. Work reduction with changed cadence, not multicore execution; visibility logic is not a dedicated-server relevance policy. |
| Spatial/collision reuse | [VehicleLosGovernor](../../../../PZO-Launcher/src/com/pzoptimizer/VehicleLosGovernor.java), [CrowdEdgeMemo](../../../../PZO-Launcher/src/com/pzoptimizer/CrowdEdgeMemo.java) | Per-frame vehicle broad phase and collision-result memoization. Worth profiling; invalidation, moving vehicles and server frame stamping require separate review. |
| Main-thread lookup work | [GameThreadPatches](../../../../PZO-Launcher/src/com/pzoptimizer/GameThreadPatches.java) | Lua table lookup fast path, ECS/state-machine caches, profiler checks, sound bounds and chunk handoff pacing. Review each independently for server applicability. |
| Lua compilation | [LuaJit](../../../../PZO-Launcher/src/com/pzoptimizer/LuaJit.java) | Background compilation of hot Lua functions; compiled execution still receives the owning Lua VM/frame. Defaults off. Does not authorize concurrent Lua callbacks. |

These are source-backed mechanisms, not measured speedups or a complete audit of
every helper in the repository. A helper's name or an initialization log is not
proof that game code calls it.

## What the client threading actually does

### BoneBatch: divide a phase, then join

1. Scheduler postupdate opens collection on the game thread.
2. Eligible zombie animation updates perform blend weights, body angles and
   deferred movement immediately. Remaining transforms are queued by player identity.
3. Parent players, recorders, ragdolls, non-zombies and incompatible bodies take
   the normal path. Reset/release/model changes cancel queued work. A repeated
   update completes that player's previous queued pass first.
4. At the scheduler boundary, eight or more queued entries use FrameWorkers;
   smaller batches execute inline. Each task owns its animation entry.
5. The caller waits until all task indices finish. Rendering receives completed
   transforms for that frame.

This is phase ownership of live animation objects, not immutable snapshots of the
whole world. It relies on no conflicting access during the batch. It also changes
when another zombie can observe a queued zombie's bones: PZO's comments accept
the previous pose inside that loop. That assumption needs checking against mods.

The sampled check recomputes **skin matrices from the resulting model transforms**;
it does not independently repeat the complete original animation update. Samples
use a 64-entry cadence and three mismatches disable batching. Worker failure also
disables batching and retries unfinished entries on the game thread. Partial
mutation before failure means retry is not a transactional rollback.

FrameWorkers uses an atomic cursor and remaining count for each batch, 1-8 workers,
and caller participation. Workers briefly spin and otherwise wait; the caller
spins then yields while waiting for completion. There is no deadline or worker
shutdown API in this pool. It is useful for small bounded phases, but a stalled
task stalls the caller. Do not apply this barrier to Lua/native calls that can
need a resource owned by that waiting caller.

### ActionParallel: pre-read, then serial continuation

The bucket loop stops eligible postupdates at ActionContext.update. Workers read
selected variables, then postupdates resume on the game thread in queue order.
At least 24 zombies are needed for worker pre-reads. Grappling and reanimated
player cases are excluded. All four hook components must report installed.

CallbackPurity examines class resources, follows a bounded known call graph and
rejects writes, monitors and unapproved external calls. This is a useful filter,
not proof of safe reads against arbitrary mods or a transformed runtime body.
Read-only methods can still observe changing state. Phase separation and the
single-player guard are essential. Sampled comparisons with live reads disable
the optimization after repeated mismatches; they do not prove every callback or
ordering correct.

### Engine options: broad threading deliberately disabled

[EngineFeaturesTuner](../../../../PZO-Launcher/src/com/pzoptimizer/EngineFeaturesTuner.java)
sets threadAnimation, threadLighting, threadAmbient, threadSound, threadWorld and
threadGridStacks false. Its comments attribute this to Kahlua crashes, FMOD races
and fork/join stalls. Native pathfinding and model-slot initialization are enabled.
Its success log mentioning threaded GridStacks/Lighting conflicts with these
assignments; the assignments are the evidence. This strongly resembles the
failure modes that motivate ApocalipseBR's ownership boundaries.

## Dedicated-server claims versus code

| Claim | Evidence in this checkout | Assessment |
| --- | --- | --- |
| Multi-core zombie pathfinding/migration | ServerHordeSimEngine.apply only sets java.util.concurrent.ForkJoinPool.common.parallelism to 4-32. No jobs or scheduler rewrite there. | Does not implement parallel zombie simulation or hibernation. |
| ForkJoinPool property increases game workers | Vanilla 42.22.2 PZForkJoinPool constructs new ForkJoinPool(availableProcessors - 1). | The JDK common-pool property does not size this explicit game pool. |
| 256 KB asynchronous world/SQLite saves | ServerChunkStreamBooster sets pzo.server.stream_buffer_size and two NIO properties. No reader of that custom property found in PZO or target game Java sources. | No demonstrated new asynchronous save pipeline or SQLite buffering from this class. |
| 4096 UDP sockets, pooled/zero-copy networking | ServerNetworkTuner and PZOServerNetGovernor set Netty/Java/custom RakNet properties. No Netty references or readers of the custom RakNet keys found in target game Java source. | Property assignments do not allocate sockets or replace RakNet transport. Native library consumption was not established; networking claims remain unsubstantiated by this Java path. |
| Real server TPS telemetry | ServerTelemetryBridge writes heap, cumulative GC counts/time-derived average, thread count, CPU and timestamp. | JSON contains no TPS or tick-duration field. It is not enough to validate simulation improvements. |

Server source references: [entry point](../../../../PZO-Launcher/src/com/pzoptimizer/server/PZOServerEntrypoint.java),
[horde](../../../../PZO-Launcher/src/com/pzoptimizer/server/ServerHordeSimEngine.java),
[save](../../../../PZO-Launcher/src/com/pzoptimizer/server/ServerChunkStreamBooster.java),
[network tuner](../../../../PZO-Launcher/src/com/pzoptimizer/server/ServerNetworkTuner.java),
[network governor](../../../../PZO-Launcher/src/com/pzoptimizer/server/PZOServerNetGovernor.java),
[telemetry](../../../../PZO-Launcher/src/com/pzoptimizer/server/ServerTelemetryBridge.java).

The server entry point DOES register the shared agent, so shared transformations
can still affect server classes. The findings above do not mean the entire server
agent is inert. Registration is conditional on startup integrity/mod guards; each
hook also needs to match its target. Verify actual rewrite logs, not just suite
initialization messages. The companion Lua mod can supply additional behavior
outside this audit's scope.

## Native pathfinding is the strongest server investigation candidate

Vanilla [PathfindNativeThread](../../decompiled/zombie/pathfind/nativeCode/PathfindNativeThread.java)
limits each iteration to two non-cancelled requests and targets 20 Hz. PZO keeps
the same worker and priority queues, but allows at least two requests and then
continues until an 8 ms budget or 512 requests. This is a soft budget: a single
search can exceed it. Redundant square updates collapse to the latest task per
coordinate; moving vehicle polygons are throttled with 0.25-tile/250-ms thresholds.
Changing vehicle geometry therefore has a freshness tradeoff.

These mechanisms improve batching and reduce invalidation traffic without
concurrent access to a shared native pathfinding world. Java source confirms the
request cap and worker structure; the claim about full native graph rebuild cost
requires native profiling. PZO also has an optional Windows native A* replacement,
disabled by default, with hard-coded DLL offsets and instruction-signature checks
in native/pzo_native.c. That is a separate exact-binary experiment, not a portable
multithreading pattern.

## Chunk streaming: do not copy the dormant loader blindly

The active MultiCoreChunkStreamer dispatcher installs a queue and sleeps. Its
queue offer/add methods call super.offer and StreamerWake.signal; neither submits
worker tasks. A source search finds dispatchChunkTask only at its declaration.
processChunkParallel is called only from that unreachable private method.

The declared per-worker buffer uses ByteBuffer.allocate, so it is a heap buffer,
despite the log saying direct NIO ring. Claimed saved time is an estimate based on
140 ms minus work duration, not a measured baseline comparison.

Even if dispatch were connected, parsing is serialized under the sanityCheck
monitor. In vanilla IsoChunk, beginLoad/endLoad synchronize only their individual
methods, not the entire parse. Holding that monitor on a new worker does not
exclude a vanilla parse already between those calls. Resetting sanity-check
fields does not establish world/object-pool ownership. This dormant path is not
evidence of a safe parallel chunk parser.

StreamerWake's narrower park/unpark change is the useful active idea. It avoids
Thread.interrupt, which can close interruptible NIO channels.

## Comparison and proposed evaluation order

ApocalipseBR already has real bounded server workers:
[ServerZombieAsyncSender](../../src/zombie/popman/ServerZombieAsyncSender.java)
uses frozen wire bytes, per-lane admission and bounded executor capacity.
[NpcDecisionPlanner](../../src/zombie/apocalipse/humans/planning/NpcDecisionPlanner.java)
has value-only selection/route jobs, deeply copied navigation and bounded slots.
[ServerHumanFactory](../../src/zombie/apocalipse/humans/ServerHumanFactory.java)
wires the worker to planner::tick, with authoritative execution staying on server.
See the inherited [async authority guide](../../../42.22.1/docs/features/2026-11-05-npc-async-authority.md)
and its explicit runtime-validation limits. Existence of these boundaries is not
proof of a measured speedup; capture/copy/validation costs may dominate.

Recommended experiments, each with an independent toggle and exact-build review:

1. **Server pathfinding throughput:** measure request age, queue depth, requests
   completed, native search time and topology updates; test request budgeting and
   square coalescing independently. Preserve task returns, cancellation and queue
   priorities. Add vehicle throttling only after freshness scenarios pass.
2. **Client batching/loading:** evaluate VBO batching, weather reuse, depth-map lock
   narrowing and texture downscaling separately. These target avoidable work and
   frame hitches without concurrent world simulation.
3. **Client bone phase:** audit all scratch/call paths for the exact client build,
   then test the phase split, lifecycle cancellation and same-frame completion.
   Track worker compute, barrier wait, batch size and fallback/mismatch counts.
4. **Server pure work:** extend existing detached planners or frozen serialization
   only where measured compute exceeds capture/publication/validation overhead.
   Independent jobs or immutable data partitions are candidates; whole IsoZombie
   updates, world loops, Lua callbacks and arbitrary native calls are not justified
   by PZO's current source.

PZO's README benchmarks compare the whole suite with stock on one client machine
and save. They do not isolate threading, prove server TPS gains, or establish
modded multiplayer correctness. Repeat controlled scenarios with per-feature
changes and report p50/p95/p99 frame/tick times, backlog and correctness, alongside
average FPS. Include warm/cold loading, driving, dense hordes, doors/moving cars,
chunk reuse, saves and shutdown where relevant.

## Validation of this investigation

Read-only call-path searches and exact 42.22.2 Java comparisons; no Java edits,
compilation, gameplay tests or benchmark runs. Report links and index entry were
checked locally. Findings describe the pinned checkout, not future releases.
