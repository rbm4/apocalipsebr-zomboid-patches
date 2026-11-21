# Legacy optimization patches: current-agent migration status

Date: **2026-11-21**. Target: **root Maven Byte Buddy agent against the original
42.22.2 game JAR**.

Status: **inventory and migration handoff only**. No optimization implementation
or runtime behavior was changed for this report.

## Executive summary

The 2026-11-18 migration baseline is a source-difference inventory, not a feature
status report. It captures the legacy implementation but does not explain which
changes are optimization features, which are ordinary gameplay/persistence
changes, which historical optimization work was later implemented, or which
parts already exist in the root agent.

The current root agent has **no general legacy simulation, spatial-index,
ServerLOS, chunk load/unload, vehicle-update, or zombie-network optimization
port declared complete**. The active [feature catalog](../features/README.md)
also explicitly says legacy engine optimizations are not yet declared migrated.
Telemetry provides basic loop/tick timing and a few population gauges, but not
the legacy optimization phases or work counters needed to demonstrate most of
these optimizations. This report treats an optimization as ported only when its
production code is registered on a real vanilla path and validated there; the
presence of a similarly named helper, worker, or telemetry module is not enough.

Meanwhile, several substantial optimizations **are already present in the
legacy patched trees**. Historical work includes entity and player indexes,
mutation-aware caches, optimized zombie packet preparation and asynchronous
packet sending, animal-update routing, and specific vehicle/simulation fixes.
They are valuable behavioral references, not agent implementations. Some
candidate work was intentionally deferred even in the legacy patch, and several
legacy changes are correctness or integration changes rather than optimizations.

## Status vocabulary

- **Agent implemented**: production behavior is installed through the root
  `AgentModules`/`AgentRuntime` pipeline and validated against the original JAR.
- **Agent partial**: only an explicitly identified part, such as telemetry or a
  bounded feature-specific worker, exists. It does not imply the optimization
  itself is ported.
- **Legacy implemented**: historical reports record an implementation in the
  version-directory patch. This is not a root-agent status or proof of a current
  live-server performance gain.
- **Legacy pending/deferred**: the latest relevant historical report explicitly
  leaves the optimization open or defers it for behavior/safety reasons.
- **Needs exact-source audit**: the 42.22.2 baseline inventory identifies the
  owner as changed, but its source diff alone does not classify the change's
  purpose or prove the behavior was carried forward unchanged from another
  build.

All runtime performance claims remain **unverified for the current agent** unless
an agent-side measurement is explicitly listed below. Historical measurements
are workload- and version-specific; they must not be used as current-agent
baselines.

## What the 2026-11-18 report did and did not establish

The [42.22.2 baseline report](../../42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-byte-buddy-migration-baseline.md)
captured the patched-source delta against the matching decompiled source:

- 375 legacy implementation Java sources: 109 modified vanilla counterparts
  and 266 added sources.
- 689 diff hunks, including all textual changes—not only feature changes.
- Optimization-relevant owners include the moving-object scheduler, entity
  systems, `IsoPlayer`/`IsoGameCharacter`, `ServerLOS`, `ServerMap`/`IsoChunk`,
  `NetworkZombiePacker`, animal synchronization, vehicle update classes,
  spatial indexes and telemetry helpers.
- Root agent and test files were recorded as context hashes, not compared as
  behavior. The report therefore cannot establish which legacy features the
  agent implements.

The baseline report explicitly left per-feature decisions, exact-JAR contracts,
hook design, coexistence/cleanup and behavior validation to later work. Its
source counts and line deltas are not optimization counts: they include NPC,
PostgreSQL, Lua, packet correctness, persistence, decompiler repairs and other
work. Its custom-class reference map is lexical, not a compiler-resolved call
graph.

The active reports index records that verification of the dated snapshot now
stops on **context drift in `pom.xml`**. Preserve the baseline as historical
evidence; do not treat it as a fresh build or as current provenance. A selected
optimization port must establish its own exact original-JAR hash, source
behavior, hook signatures and tests.

## Current root-agent state

### Implemented foundation, not optimization ports

The root [module registry](../../src/main/java/cloud/apocalipse/pz/agent/AgentModules.java)
registers telemetry, Workshop, PostgreSQL/dictionary/player, NPC, Lua, and async
account modules. It does not register a general simulation-optimization module
or a legacy optimization feature module.

The [telemetry feature guide](../features/README.md) and
[42.22.2 telemetry report](../../42.22.2/docs/features/2026-11-18-byte-buddy-server-telemetry-basics.md)
describe what is actually measured in the agent:

- Completed simulation tick and outer loop timing, including the 100 ms
  over-budget tick count.
- Snapshot-time online-player and active-current-cell zombie gauges.
- Existing Lua event timing on its separate vanilla hook.

The optimization-specific legacy phase timings/counters, moving-object
histograms, pending-cell gauge, JVM measurements, and most discovery/skip/
candidate counters are not ported. Basic tick timing can show that a tick was
slow; it cannot tell which patch would help or whether a cache reduced work.

Two other agent capabilities must not be conflated with these optimization
patches:

- The NPC module has a bounded, detached planner for selected NPC decisions and
  navigation. Native world access and NPC execution remain server-owned. It is
  not a replacement for the legacy general simulation worker or the zombie
  packet sender. See the [NPC agent guide](../features/npc-engine.md) and
  [42.22.1 async-authority report](../../42.22.1/docs/features/2026-11-05-npc-async-authority.md).
- Lua API, account/admin, entity-persistence, PostgreSQL and NPC persistence
  workers perform bounded feature-specific background work. They are not ports
  of main-loop simulation, LOS, packet-selection or world-update optimizations.

Accordingly, the optimization-specific root-agent status is:

| Area | Root-agent status |
| --- | --- |
| Legacy simulation/scheduler/index optimizations | **Not ported** |
| General player update, inventory, proximity and sound indexes | **Not ported** |
| Server LOS query/scheduling/cancellation optimizations | **Not ported** |
| Zombie packet preparation and async network sending | **Not ported** |
| Animal synchronization routing | **Not ported** |
| Chunk load/unload and world lifecycle algorithms | **Not ported** |
| Vehicle update selection/caches | **Not ported** |
| Optimization-specific child timings and work counters | **Partial**: basic loop/tick telemetry only |
| Bounded NPC decision/navigation planning | **Implemented as a distinct NPC feature**, not a general optimization port |

This status is about the root agent, not whether the old server patch has those
changes. No deployment or agent-side performance comparison is established by
this report.

## Legacy optimization work already reported as implemented

These are historical source/fixture results, chiefly from 42.21.0, carried into
later version snapshots to varying extents. They should be checked against the
exact 42.22.2 diff before implementing a root port. The historical tests establish
their documented invariants, not production speedup or current-agent behavior.

| Workstream | Historical implementation recorded | Current root-agent distinction |
| --- | --- | --- |
| Entity scheduling and due meta simulation | Persistent due-selection indexes, batched elapsed ticks, server entity simulation membership and using-player indexing. Legacy fixtures exercised lifecycle, ordering and cadence. | No equivalent agent hook/index. Candidate migration must use vanilla entry points and preserve add/remove, ID changes, pooling and delayed operations. |
| Player/moving-object paths | Thermal clothing cache ownership, server near-vehicle skip/spatial lookup, recursive inventory updater routes, mechanics-item expiry checks, sound-stress spatial lookup, scheduler/index lifecycle work. | No player/scheduler optimization hook or production counter in the agent. Inventory weight caching was not implemented even in the old patch. |
| Server LOS and zombie perception | Legacy `ServerLOS` already schedules LOS work at roughly a two-second interval; completion shares a spatial zombie view and keeps order and eligibility/visibility predicates. | No agent `ServerLOS` optimization hook. Worker cancellation and safe map-mutation barriers remain a synchronization concern, not a proven solved fast path. |
| Zombie packet preparation | Preparation separates per-frame AI/state/thump bookkeeping from demand-driven packet fields; selected zombies are prepared once across recipients. Enum/path/vector/flag helper work is reduced while public contracts are retained. | No agent packet-preparation hook. Do not equate NPC packet overlays or basic telemetry with this cache/selection lifecycle. |
| Zombie packet transmission | A bounded asynchronous sender freezes packet fields on the main thread, uses isolated worker buffers and raw transport, limits one outstanding batch per connection, retains retry/backpressure behavior, and shuts down before the network peer. | No agent async zombie sender. Snapshot/selection cost remains main-thread work in the legacy design, and asynchronous transport changes timing/freshness semantics. |
| Animal synchronization | Legacy spatial connection coverage routes changed animals to candidate clients while retaining exact relevance checks; overflow packet processing is iterative and bounded. | No agent animal-sync hook or routing index. |
| Vehicle idle work | A narrowly authorized SIXTEENTH update cadence for one idle category; ordered device/light capability view, callback-safe mutation handling, model lookup/same-call square reuse and server sound bookkeeping fast path. | No agent vehicle hooks. The cadence exception is intentional behavior and needs explicit compatibility tests; it is not just an invisible cache. |
| Lua event dispatch | Stable-list fast path reduces repeated callback membership checks while preserving list mutation behavior. | No agent port of the dispatcher optimization; the agent's Lua feature hooks do not imply replacement of native event-list behavior. |
| Small bounded fixes | Zombie culling zero-quota/fulfilled-quota lookup avoidance, building bookkeeping simplification and server vocal traversal skip were recorded as implemented. Corpse LOS prefilter and IsoThumpable no-work checks were also implemented. | None are active agent optimizations. Each must be independently justified against the original method and preserve callback/ordering contracts. |
| Removal/index support | Global entity removal uses identity-position bookkeeping while retaining vanilla swap/listener/delayed-operation semantics; sound append indexing and animal-zone topology/deduplication changes are also recorded. | No general agent-owned collection indexes or mutation adapters ported for these paths. |

Key references: [simulation optimization review](../../42.21.0/docs/reports/2026-10-03-simulation-optimization-review.md),
[unload/simulation algorithm review and implementation updates](../../42.21.0/docs/reports/2026-10-05-unload-simulation-algorithm-review.md),
[player/zombie packet hotspots and implementation](../../42.21.0/docs/reports/2026-10-05-player-zombie-packet-hotspots.md),
and [immutable async zombie sending](../../42.21.0/docs/features/2026-10-12-async-zombie-sending.md).

## Remaining optimization work by subsystem

The following is the current migration backlog, separated from the historical
implemented list above. “Remaining” means absent from the root agent or explicitly
open in the historical evidence; it does not mean every item is approved for the
next implementation session.

### 1. Measurement and attribution

**Agent state: partial. Legacy state: richer telemetry exists in patch sources.**

Before asserting that a port improves capacity, add only the telemetry needed by
that bounded feature. The agent currently lacks most internal optimization phases
and counters. Relevant outstanding measurements include:

- Child phases that distinguish cell/world, entity systems, `ProcessIsoObject`,
  corpses, animal synchronization, vehicle Java preparation, physics calls,
  zombie packet snapshot/selection/worker transport, LOS wait/candidate work,
  and load/unload integration.
- Work counts for registered/due/skipped objects, actual removal attempts,
  LOS/zombie candidates, packet selections/preparations/bytes, queued or
  rejected sends, load/unload objects and squares, vehicle visits and selected
  parts.
- Clear definitions for inclusive child timings, worker queue delay versus CPU,
  reset windows, unavailable metrics and snapshot populations.

The 42.21 reports contain older server captures (including 32-player packet
windows and a later unload/simulation capture). These are motivation and
algorithm evidence only. They are not a current-agent baseline. A future
measurement session should capture comparable workloads on the current original
JAR before selecting a high-cost target.

### 2. Simulation scheduling, ECS and update traversal

**Agent state: not ported. Legacy state: several optimizations implemented; a
full-system replacement is not.**

Candidate owners in the baseline include `MovingObjectUpdateScheduler`,
`MovingObjectUpdateSchedulerUpdateBucket`, `EngineEntityManager`,
`GameEntityManager`, `EntitySimulation`, `UsingPlayerUpdateSystem`,
`ServerEntitySimulationIndex`, `ServerUsingPlayerIndex`, and the craft/resource/
fluid systems.

The prior implementation reduced repeated work and added due/meta and membership
indexes; it did not eliminate every broad scan. Remaining items include:

- Profile and, if still material, optimize global engine-array removals and
  preserve identity, swap, listener, delayed-op and re-entry behavior.
- Determine whether a distinct `ProcessIsoObject` due-view/index is justified.
  The full phase-filter traversal remains; timings and a narrow thumpable
  no-work predicate do not mean the scan itself was removed.
- Preserve active insertion order, ID/phase changes, public collection
  mutations, client behavior and meta-to-loaded transitions.
- Keep elapsed simulation-tick cadence distinct from server-frame cadence.
  Do not convert a cadence optimization into broad throttling.

### 3. Player and character update work

**Agent state: not ported. Legacy state: most identified indexes/caches are
implemented; some candidates were deliberately not done.**

Potential owners include `IsoPlayer`, `IsoGameCharacter`, `Thermoregulator`,
`ItemContainer`, `ServerInventoryUpdateIndex`, `ServerPlayerSpatialQueries`,
`ServerSoundStressIndex` and `WorldSoundManager`.

The historical implementation already covers the clothing-cache ownership bug,
non-sneaking dedicated-server vehicle-query skip, spatial vehicle/sound queries,
inventory updater discovery and mechanics expiry behavior. Remaining work is:

- Revalidate each of those boundaries against exact 42.22.2 vanilla bytecode and
  the target patch diff before porting; their old tests are not agent tests.
- Keep inventory-weight traversal uncached unless a profile justifies it and
  complete invalidation can be proven for nested contents, ammo, equipment and
  item mutation.
- Retain exact distance metrics, list ordering, callback mutation semantics,
  fallback behavior for externally supplied plain collections, and same-frame
  invalidation.
- Avoid changing health, movement, endurance, temperature, cooking or item
  gameplay cadence as a shortcut.

### 4. Server LOS, perception and worker lifecycle

**Agent state: not ported. Legacy state: spatial completion query implemented;
full cancellation redesign remains open.**

The legacy source modifies `ServerLOS`, `IsoPlayer`, `LosUtil` and related
spatial helpers. The prior optimization shares spatial zombie candidate views
between player LOS completions, but retains scheduling, target order and final
predicates. The unresolved worker concern is that map mutation/suspension can
wait for full LOS passes; simply checking a generation before/after a pass does
not make it safe to detach workers from live squares.

An agent port must establish exact native scheduling/worker entry points and
ownership, query invalidation on teleports/chunk unload/reload and incoming
position changes, and a barrier/cancellation protocol that cannot access squares
after invalidation. Do not claim a cancellation optimization by adding a token
check without tracing acquisition, active jobs and teardown.

### 5. Zombie packet preparation, sending and network overload

**Agent state: not ported. Legacy state: preparation and bounded async sender
implemented, with explicit semantic tradeoffs.**

Relevant owners include `NetworkZombiePacker`, `NetworkZombieManager`,
`ZombiePacket`, `NetworkZombieAI`, `NetworkZombieVariables`,
`ServerZombieAsyncSender`, and connection/ownership indexes.

The root agent still needs a vanilla-hooked port for any selected part:

- Separate stateful per-frame prediction, ownership, thump/grapple and packet
  field preparation before considering demand-driven selection.
- Preserve incoming packet invalidation, late requests, relay selection, packet
  capacity, deletion/authorization ordering, recipient fairness and original
  wire encoding.
- Keep mutable native packet preparation on the server owner; do not move it to
  connection workers.
- If sending is ported, preserve bounded admission, exact session binding,
  retries, retained requests/deletions, cancellation, backpressure diagnostics
  and shutdown-before-peer-destruction. Measure packet freshness and dropped/
  delayed work as well as tick time.
- Include the known semantics: captured bytes can be older than the old
  synchronous send point; timers advance on admission; native transport cannot
  be canceled mid-call; direct raw sends bypass optional `PacketType.send`
  debug logging. This is not automatically equivalent to vanilla behavior.

See the detailed
[packet hotspot report](../../42.21.0/docs/reports/2026-10-05-player-zombie-packet-hotspots.md)
and [async sender contract](../../42.21.0/docs/features/2026-10-12-async-zombie-sending.md).

### 6. Animal synchronization and virtual-animal lifecycle

**Agent state: not ported. Legacy state: network routing implemented; lifecycle
and snapshot optimization remains a candidate.**

The baseline includes `AnimalSynchronizationManager`,
`ServerAnimalUpdateCoverage`, `ServerAnimalPerceptionGrid`, `AnimalZones`,
`DesignationZoneAnimal` and related topology helpers. Preserve exact `RelevantTo`
checks, change/request/delete handling, reliable cadence, ownership-sensitive
state, split-screen behavior and packet size caps while routing candidates.
Spatial routing cannot eliminate unavoidable fan-out when all clients need the
same herd.

Virtual-animal list snapshots and chunk membership were deliberately not
flattened into a simplistic live iterator. Their copied snapshots protect
concurrent/moving membership behavior. Profile capture, movement between chunks,
zone edits and duplicate/overlap order before considering that separate work.

### 7. Chunk loading, world integration and unloading

**Agent state: not ported. Legacy state: some lifecycle/index support exists;
the unload traversal is not generally eliminated.**

The baseline identifies `IsoChunk`, `IsoCell`, `IsoWorld`, `ServerMap`,
`LoadedAreas`, square/object indexes and removal helpers. Historical capture
sequences 327–336 measured unload at about 3.684 ms per simulation tick, with
square unload around 3.315 ms and object unload around 1.982 ms in that workload.
Those are old inclusive timings, not current agent measurements.

Important separation:

- Existing lifecycle indexes benefit some unload callees, and global engine-array
  removal is optimized in the later legacy implementation.
- `IsoChunk.removeFromWorld` still traverses squares/objects; the report did not
  find a dedicated replacement for the entire unload algorithm.
- Load integration (including `doLoadGridsquare` and border recalculation) was
  called out for visibility, not arbitrary bulk skipping. Object callbacks,
  adjacency refresh, lights/devices and list mutations constrain safe batching.
- `IsoChunk`/`ServerMap` changes can include save, queue and failure-ordering
  contracts, not only speedups. Keep queue completion, failed-cell cleanup,
  partial unload, save lifecycle and shutdown behavior intact.

The next port should profile load and unload separately, count actual objects,
squares and callbacks, and identify whether time is traversal, native work,
allocation or waiting. A faster outer timing without equivalent lifecycle
coverage is not acceptance.

### 8. World-object cadence, corpses and time-driven maintenance

**Agent state: not ported. Legacy state: bounded predicates/prefilters exist;
several larger scheduling proposals remain deferred.**

Owners include `IsoCell.ProcessIsoObject`, `IsoDeadBody`, `IsoThumpable`,
`IsoBuilding`, `FishSchoolManager`, `IsoWorldInventoryObject`, item aging and
virtual-animal systems.

Remaining or constrained work:

- A full `ProcessIsoObject` due-bucket view remains open. Public list/removal
  mutations, stable update order, ID-derived phase changes, traps/generators and
  re-entry make add/remove hooks alone insufficient.
- Full corpse deadline scheduling was explicitly deferred because random
  skeleton trials, continuously changing animal `deathAge`, shared rot-stage
  timing/order, prompt wakeup, manual stage changes, player/animal exemptions,
  options and clock rollback must be preserved. Only the LOS prefilter was
  implemented in the cited report.
- Fishing expiry scans and virtual-animal snapshot work remain candidates; their
  observed legacy costs were small/secondary in the cited workload.
- Building bookkeeping and server vocal traversal fixes were implemented as
  small bounded changes; remeasure before prioritizing.
- Food/compost aging is not a single harmless timer: temperature, cooking,
  eggs, worm aging, item mutation and conversion effects share update methods.
  Do not throttle the entire method without a lifecycle/effect audit.

### 9. Vehicles and physics

**Agent state: not ported. Legacy state: targeted idle-path changes and timing
exist; broad part/physics selection remains open.**

The baseline includes `BaseVehicle`, `VehicleParts`, `VehiclePart`,
`VehicleManager`, `WorldSimulation` and vehicle database paths. Prior work adds
stage timings, a server idle capability view and selected local reuse. One
specific SIXTEENTH cadence change intentionally alters callback/device cadence
for vehicles whose only former FULL reason was `needPartsUpdate`; it is not a
behavior-neutral cache.

Fixed Bullet integration/substeps were retained. Broad active/sleeping vehicle
selection, per-part deadlines, cross-tick square/floor caches and physics-step
changes remain unproven or pending. Measure Java preparation separately from
native physics and readback. Do not change fixed timestep or skip callbacks
without proving wakeups for contacts, towing, authority changes, chunk arrival,
custom parts and mod callbacks.

### 10. Secondary candidates

**Agent state: not ported. Historical candidates should be re-profiled, not
automatically queued.**

- Fishing deadline/expiry indexing; preserve strict deadline comparison,
  same-minute inserts, option changes, clock changes and direct timestamp writes.
- Virtual-animal list/snapshot lifecycle work; preserve chunk movement and
  mutation safety.
- Inventory weight totals; not previously implemented because the invalidation
  surface was broad and call frequency unmeasured.
- Full load-phase attribution and other lower-cost counters.
- Any newly found 42.22.2-only optimization in the baseline's changed sources;
  inspect its per-source diff and compare intent with same-build vanilla before
  adding it to a feature scope.

## Recommended use in the next implementation session

1. Select **one bounded optimization behavior**, not a version-wide batch.
   The class inventory is a search map, not an approved migration queue.
2. Read the exact 42.22.2 patched diff and the matching vanilla implementation.
   Separate optimization intent from telemetry, gameplay changes, persistence,
   networking correctness/security and decompiler repairs.
3. Trace callers, method signatures, server/client role, worker ownership,
   mutation paths, collection exposure, invalidation, chunk/object reuse,
   exception behavior and shutdown.
4. Choose original-JAR Byte Buddy entry points. Register a real feature module,
   declare `hookChecks()`, and ensure transformed vanilla execution reaches the
   production implementation. Do not add a patched-class fallback.
5. Add a focused differential/production-code test that exercises the transformed
   entry point, result/order, vanilla continuation or deliberate skip, exception
   handling and lifecycle cleanup. Use exact original-JAR class resolution.
6. Port only directly required metrics: define population, timing boundaries,
   units, window/reset behavior and queue/drop semantics. Do not fabricate zero
   for unavailable values.
7. Run focused Maven tests and package when the shaded artifact/linkage changes.
   Record exact JAR hash, command, result and remaining runtime scenarios.
   Deployment and live-server performance acceptance remain separate.

Good initial candidates for separate sessions are (a) agent-side optimization
telemetry to establish a comparable baseline, (b) a low-risk server-only
simulation/cache behavior with clear lifecycle hooks, or (c) a single measured
network or load/unload hotspot. The historical implementation order is not
automatically the right order for the root agent; current original-JAR behavior
and fresh measurements should decide.

## References and limits

- [42.22.2 Byte Buddy migration baseline](../../42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-byte-buddy-migration-baseline.md)
  and [per-source class inventory](../../42.22.2/docs/reports/2026-11-18-byte-buddy-migration-baseline/2026-11-18-class-inventory.md).
- [42.21.0 simulation optimization review](../../42.21.0/docs/reports/2026-10-03-simulation-optimization-review.md).
- [42.21.0 unload/simulation algorithm review](../../42.21.0/docs/reports/2026-10-05-unload-simulation-algorithm-review.md).
- [42.21.0 player/zombie packet hotspots](../../42.21.0/docs/reports/2026-10-05-player-zombie-packet-hotspots.md).
- [42.21.0 asynchronous zombie sending](../../42.21.0/docs/features/2026-10-12-async-zombie-sending.md).
- [Current feature catalog](../features/README.md), [migration workflow/status](../migrations/README.md),
  and [server telemetry contract](../../42.22.2/docs/features/2026-11-18-byte-buddy-server-telemetry-basics.md).

The historical reports span different patch versions, source snapshots and
workloads. Their implementation notes do not prove every optimization is
byte-for-byte or behaviorally identical in 42.22.2. The baseline's source diffs
are the starting point for that exact-build verification. No current-agent
optimization throughput, CPU saving, live-server capacity gain or deployment
acceptance is claimed here.
